package com.convenientclipboard.storage;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.files.FileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProviderService {
    public record ProviderInfo(UUID id,String name,String endpoint,String region,String bucket,boolean enabled,int priority,boolean hasCredentials,String type,String mountPath,String mountId) {}
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final FileRepository files;
    private final StorageCoordinator coordinator;
    private final MountedStorage mounted;
    public ProviderService(JdbcTemplate jdbc,CredentialCipher cipher,FileRepository files,StorageCoordinator coordinator,MountedStorage mounted) { this.jdbc=jdbc;this.cipher=cipher;this.files=files;this.coordinator=coordinator;this.mounted=mounted; }
    private static final RowMapper<StorageProvider> MAPPER=(rs,n)->new StorageProvider(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("endpoint"),rs.getString("region"),rs.getString("bucket"),rs.getString("access_key_encrypted"),rs.getString("secret_key_encrypted"),rs.getBoolean("enabled"),rs.getInt("priority"),rs.getString("type"),rs.getString("mount_path"),rs.getString("mount_id"));
    public StorageProvider get(UUID id) { return jdbc.query("select * from storage_providers where id=?",MAPPER,id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"存储器不存在")); }
    public List<StorageProvider> enabled() { return jdbc.query("select * from storage_providers where enabled order by priority,created_at",MAPPER); }
    public List<ProviderInfo> list() { return jdbc.query("select * from storage_providers order by priority,created_at",MAPPER).stream().map(this::info).toList(); }
    private ProviderInfo info(StorageProvider p) { return new ProviderInfo(p.id(),p.name(),p.endpoint(),p.region(),p.bucket(),p.enabled(),p.priority(),"S3".equals(p.type()),p.type(),p.mountPath(),p.mountId()); }
    public ProviderInfo save(UUID id,ProviderRequest request) {
        return coordinator.locked(()-> {
            StorageProvider previous=id==null?null:get(id);
            UUID providerId=id==null?UUID.randomUUID():id;
            if(!List.of("S3","NFS","SMB").contains(request.type())) throw new ApiException(HttpStatus.BAD_REQUEST,"不支持的存储类型");
            if(previous!=null && !previous.type().equals(request.type()) && files.providerUsed(id)) throw new ApiException(HttpStatus.CONFLICT,"此存储器仍有文件，不能修改存储类型，请新增存储器");
            String endpoint=null,region=null,bucket=null,accessEncrypted=null,secretEncrypted=null,mountPath=null,mountId=null;
            if("S3".equals(request.type())) {
                URI uri;
                try { uri=URI.create(request.endpoint()); }
                catch(IllegalArgumentException|NullPointerException ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"S3 存储端点必须是有效的 HTTP(S) URL"); }
                if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null)
                    throw new ApiException(HttpStatus.BAD_REQUEST,"S3 存储端点必须是有效的 HTTP(S) URL");
                if(request.region()==null || request.region().isBlank() || request.bucket()==null || request.bucket().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,"S3 存储器需要区域和桶名称");
                endpoint=request.endpoint();region=request.region();bucket=request.bucket();
                String access=request.accessKey(),secret=request.secretKey();
                boolean reusable=previous!=null && "S3".equals(previous.type());
                if((access==null || access.isBlank()) && !reusable || (secret==null || secret.isBlank()) && !reusable) throw new ApiException(HttpStatus.BAD_REQUEST,"新增 S3 存储器需要 Access Key 和 Secret Key");
                accessEncrypted=access==null || access.isBlank()?previous.accessKeyEncrypted():cipher.encrypt(access);
                secretEncrypted=secret==null || secret.isBlank()?previous.secretKeyEncrypted():cipher.encrypt(secret);
            } else {
                mountPath=request.mountPath();mountId=request.mountId();
                if(mountPath==null || mountPath.isBlank() || mountId==null || !mountId.matches("[A-Za-z0-9_-]{1,128}")) throw new ApiException(HttpStatus.BAD_REQUEST,"NFS/SMB 需要挂载路径和 1–128 位字母、数字、下划线或连字符组成的挂载标识");
            }
            if(previous!=null && files.providerUsed(id) && (!previous.type().equals(request.type()) || !Objects.equals(previous.endpoint(),endpoint) || !Objects.equals(previous.bucket(),bucket) || !Objects.equals(previous.region(),region) || !Objects.equals(previous.mountPath(),mountPath) || !Objects.equals(previous.mountId(),mountId)))
                throw new ApiException(HttpStatus.CONFLICT,"此存储器仍有文件，不能修改类型、端点、区域、桶或挂载信息，请新增存储器");
            StorageProvider saved=new StorageProvider(providerId,request.name(),endpoint,region,bucket,accessEncrypted,secretEncrypted,request.enabled(),request.priority(),request.type(),mountPath,mountId);
            // Disabling a disconnected share must remain possible; its immutable identity is still checked above.
            if(saved.mounted() && (saved.enabled() || previous==null)) {
                try { mounted.validate(saved); }
                catch(IOException ex) { throw new ApiException(HttpStatus.BAD_REQUEST,ex.getMessage()); }
            }
            if(previous==null) jdbc.update("insert into storage_providers(id,name,endpoint,region,bucket,access_key_encrypted,secret_key_encrypted,enabled,priority,type,mount_path,mount_id) values(?,?,?,?,?,?,?,?,?,?,?,?)",providerId,saved.name(),endpoint,region,bucket,accessEncrypted,secretEncrypted,saved.enabled(),saved.priority(),saved.type(),mountPath,mountId);
            else jdbc.update("update storage_providers set name=?,endpoint=?,region=?,bucket=?,access_key_encrypted=?,secret_key_encrypted=?,enabled=?,priority=?,type=?,mount_path=?,mount_id=? where id=?",saved.name(),endpoint,region,bucket,accessEncrypted,secretEncrypted,saved.enabled(),saved.priority(),saved.type(),mountPath,mountId,providerId);
            return info(get(providerId));
        });
    }
    public void delete(UUID id) {
        coordinator.locked(()-> {
            get(id);
            if(files.providerUsed(id)) throw new ApiException(HttpStatus.CONFLICT,"存储器仍有文件，不能删除；停用仅阻止新增转存，不影响已有文件下载");
            jdbc.update("delete from storage_providers where id=?",id); return null;
        });
    }
}
