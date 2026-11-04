package com.convenientclipboard.files;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import com.convenientclipboard.storage.LocalStorage;
import com.convenientclipboard.storage.SettingsService;
import com.convenientclipboard.storage.StorageAccess;
import com.convenientclipboard.storage.StorageChanged;
import com.convenientclipboard.storage.StorageCoordinator;
import com.convenientclipboard.storage.StorageSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class FileService {
    private static final Logger log=LoggerFactory.getLogger(FileService.class);
    private static final Duration RETENTION=Duration.ofDays(7);
    private final FileRepository files;
    private final LocalStorage local;
    private final StorageAccess storage;
    private final SettingsService settings;
    private final StorageCoordinator coordinator;
    private final AppProperties properties;
    private final ApplicationEventPublisher events;
    private final SecureRandom random=new SecureRandom();
    public FileService(FileRepository files,LocalStorage local,StorageAccess storage,SettingsService settings,StorageCoordinator coordinator,AppProperties properties,ApplicationEventPublisher events) {
        this.files=files;this.local=local;this.storage=storage;this.settings=settings;this.coordinator=coordinator;this.properties=properties;this.events=events;
    }
    public FileInfo upload(MultipartFile upload,boolean permanent,AppPrincipal principal) {
        if(permanent && principal==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"请登录后选择永久保存");
        UUID id=UUID.randomUUID();
        String key=id.toString();
        StoredFile file;
        try {
            file=coordinator.locked(()->{
                StorageSettings limits=settings.get();
                long maximum=limits.maxUploadBytes();
                if(upload.getSize()>maximum) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"文件超过当前单次上传限制");
                long available=limits.localCapacityBytes()-local.usedBytes();
                if(upload.getSize()>available || upload.getSize()>local.usableBytes()) throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE,"本地存储空间不足，请稍后重试或联系管理员");
                Instant now=Instant.now();
                byte[] token=new byte[24]; random.nextBytes(token);
                long size;
                try(InputStream input=upload.getInputStream()) { size=local.write(key,input,Math.min(maximum,available)); }
                catch(IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"文件写入失败，请重试"); }
                StoredFile result=new StoredFile(id,principal==null?null:principal.id(),safeFilename(upload.getOriginalFilename()),safeContentType(upload.getContentType()),size,key,Base64.getUrlEncoder().withoutPadding().encodeToString(token),permanent,permanent?null:now.plus(RETENTION),"LOCAL",null,now);
                files.insert(result);
                return result;
            });
        } catch(RuntimeException ex) {
            try { local.delete(key); } catch(IOException cleanup) { log.warn("Deferred cleanup for local object {}",id); }
            throw ex;
        }
        // Event delivery must not turn a committed upload into a missing local object.
        try { events.publishEvent(new StorageChanged()); }
        catch(RuntimeException ex) { log.warn("Maintenance notification failed; scheduled maintenance will retry"); }
        return info(file);
    }
    public static String safeFilename(String original) {
        if(original==null || original.isBlank()) return "file";
        String normalized=original.replace('\\','/');
        String name=normalized.substring(normalized.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","_").strip();
        if(name.isBlank() || name.equals(".") || name.equals("..")) name="file";
        return name.length()>255?name.substring(0,255):name;
    }
    private static String safeContentType(String type) { return type==null || type.length()>255 || type.chars().anyMatch(Character::isISOControl)?"application/octet-stream":type; }
    public FileInfo info(StoredFile file) {
        return new FileInfo(file.id(),file.originalName(),file.sizeBytes(),file.contentType(),file.permanent(),file.expiresAt(),file.createdAt(),file.location(),properties.publicBaseUrl().replaceAll("/+$","")+"/s/"+file.shareToken());
    }
    public List<FileInfo> owned(AppPrincipal principal) { return files.owned(requireUser(principal).id()).stream().map(this::info).toList(); }
    public FileInfo share(String token) { return info(activeShare(token)); }
    private StoredFile activeShare(String token) {
        if(!token.matches("[A-Za-z0-9_-]{32}")) throw new ApiException(HttpStatus.NOT_FOUND,"共享文件不存在");
        StoredFile file=files.byShare(token).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"共享文件不存在"));
        requireActive(file); return file;
    }
    public record Download(StoredFile file,InputStream input) {}
    public Download download(String token) {
        return coordinator.locked(()-> {
            StoredFile file=activeShare(token);
            try { return new Download(file,storage.open(file)); }
            catch(IOException|RuntimeException ex) {
                log.warn("Cannot open file {} from {}",file.id(),file.location());
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"文件存储暂时不可用，请稍后重试");
            }
        });
    }
    public FileInfo retention(UUID id,boolean permanent,AppPrincipal principal) {
        return coordinator.locked(()-> {
            StoredFile file=files.find(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"文件不存在"));
            if(!requireUser(principal).id().equals(file.ownerId())) throw new ApiException(HttpStatus.FORBIDDEN,"仅文件所有者可以修改保存期限");
            requireActive(file);
            files.retention(id,permanent,permanent?null:Instant.now().plus(RETENTION));
            return info(files.find(id).orElseThrow());
        });
    }
    public void delete(UUID id,AppPrincipal principal) {
        coordinator.locked(()-> {
            StoredFile file=files.find(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"文件不存在"));
            AppPrincipal user=requireUser(principal);
            if(!user.id().equals(file.ownerId()) && !"ADMIN".equals(user.role())) throw new ApiException(HttpStatus.FORBIDDEN,"无权删除此文件");
            try { storage.delete(file); }
            catch(IOException|RuntimeException ex) { throw new ApiException(HttpStatus.BAD_GATEWAY,"存储器删除失败，文件记录已保留，请重试"); }
            files.delete(id); return null;
        });
    }
    private static AppPrincipal requireUser(AppPrincipal principal) {
        if(principal==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"请先登录");
        return principal;
    }
    private static void requireActive(StoredFile file) { if(file.expired(Instant.now())) throw new ApiException(HttpStatus.GONE,"共享文件已过期"); }
}
