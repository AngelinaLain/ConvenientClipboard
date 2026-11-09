package com.convenientclipboard.uploads;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.files.*;
import com.convenientclipboard.storage.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class UploadService {
    private static final Logger log=LoggerFactory.getLogger(UploadService.class);
    private static final Duration ACTIVE_LIFETIME=Duration.ofMinutes(10), RESULT_LIFETIME=Duration.ofDays(7);
    private final UploadRepository tasks;
    private final UploadStorage parts;
    private final FileRepository files;
    private final FileService fileService;
    private final LocalStorage local;
    private final SettingsService settings;
    private final StorageCoordinator coordinator;
    private final ApplicationEventPublisher events;
    public UploadService(UploadRepository tasks,UploadStorage parts,FileRepository files,FileService fileService,
                         LocalStorage local,SettingsService settings,StorageCoordinator coordinator,ApplicationEventPublisher events) {
        this.tasks=tasks;this.parts=parts;this.files=files;this.fileService=fileService;this.local=local;
        this.settings=settings;this.coordinator=coordinator;this.events=events;
    }
    public UploadStatus create(UploadRequest request,String token,AppPrincipal principal) {
        String tokenHash=tokenHash(token);
        validate(request);
        return coordinator.uploadLocked(request.id(),() -> {
            UploadTask existing=tasks.find(request.id());
            if(existing!=null) {
                authorize(existing,tokenHash,principal);
                requireActive(existing);
                if(!existing.originalName().equals(request.originalName()) || !existing.contentType().equals(request.contentType())
                        || existing.sizeBytes()!=request.sizeBytes() || existing.permanent()!=request.permanent()
                        || !existing.chunkHashes().equals(request.chunkHashes()))
                    throw new ApiException(HttpStatus.CONFLICT,"该上传任务的文件信息或分片清单已改变");
                if(!existing.completed()) coordinator.locked(() -> {
                    requireActive(tasks.find(existing.id()));
                    requireUploadAllowed(existing.ownerId(),settings.get());
                    tasks.touch(existing.id(),Instant.now().plus(ACTIVE_LIFETIME));
                    return null;
                });
                return status(tasks.find(existing.id()));
            }
            if(request.permanent() && principal==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"请登录后选择永久保存");
            return coordinator.locked(() -> {
                if(files.find(request.id()).isPresent()) throw new ApiException(HttpStatus.CONFLICT,"该任务标识已用于保存文件，请创建新任务");
                StorageSettings limits=settings.get();
                requireUploadAllowed(principal==null?null:principal.id(),limits);
                if(request.sizeBytes()>limits.maxUploadBytes()) throw tooLarge();
                long available=limits.localCapacityBytes()-local.usedBytes()-coordinator.reservedUploadBytes(null);
                if(request.sizeBytes()>available || request.sizeBytes()>local.usableBytes()) throw noSpace();
                UploadTask task=new UploadTask(request.id(),principal==null?null:principal.id(),tokenHash,
                        request.originalName(),request.contentType(),request.sizeBytes(),request.permanent(),
                        List.copyOf(request.chunkHashes()),"UPLOADING",Instant.now().plus(ACTIVE_LIFETIME),null);
                tasks.insert(task);
                return status(task,false);
            });
        });
    }
    public UploadStatus get(UUID id,String token,AppPrincipal principal) {
        String hash=tokenHash(token);
        return coordinator.uploadLocked(id,() -> status(access(id,hash,principal)));
    }
    public UploadStatus chunk(UUID id,int index,String token,AppPrincipal principal,InputStream input,long contentLength) {
        String hash=tokenHash(token);
        return coordinator.uploadLocked(id,() -> {
            UploadTask task=access(id,hash,principal);
            requireUploadAllowed(task.ownerId(),settings.get());
            if(index<0 || index>=task.totalChunks()) throw new ApiException(HttpStatus.BAD_REQUEST,"分片序号无效");
            long expected=task.chunkBytes(index);
            if(contentLength>expected) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"分片超过预期大小");
            if(contentLength>=0 && contentLength<expected) throw new ApiException(HttpStatus.BAD_REQUEST,"分片长度与文件清单不符");
            try {
                if(!task.completed()) {
                    parts.clearStaging(id);
                    if(expected>local.usableBytes()) throw noSpace();
                }
                // Even an already received/completed chunk must match this request's actual bytes.
                Runnable progress=task.completed()?() -> {}:progress(task.id());
                parts.receive(task,index,input,task.completed(),progress);
                if(!task.completed()) {
                    requireActive(tasks.find(id));
                    tasks.received(task,index);
                    tasks.touch(id,Instant.now().plus(ACTIVE_LIFETIME));
                }
                return status(tasks.find(id),false);
            } catch(IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"分片写入中断，请重试此分片"); }
        });
    }
    public UploadStatus complete(UUID id,String token,AppPrincipal principal) {
        String hash=tokenHash(token);
        UploadStatus result=coordinator.uploadLocked(id,() -> {
            UploadTask task=access(id,hash,principal);
            if(task.completed()) return status(task);
            requireUploadAllowed(task.ownerId(),settings.get());
            Runnable progress=progress(id);
            UploadStatus received=status(task,true,progress);
            if(received.receivedChunks().size()!=task.totalChunks())
                throw new ApiException(HttpStatus.CONFLICT,"文件尚有未完成的分片");
            if(task.sizeBytes()>settings.get().maxUploadBytes()) throw tooLarge();
            if(task.sizeBytes()>local.usableBytes()) throw noSpace();
            Path merged=null;
            try {
                // Disk merge holds this task's lock; network reception never holds the global quota lock.
                merged=parts.merge(task,progress);
                Path assembled=merged;
                return coordinator.locked(() -> {
                    requireActive(tasks.find(id));
                    StorageSettings limits=settings.get();
                    requireUploadAllowed(task.ownerId(),limits);
                    if(task.sizeBytes()>limits.maxUploadBytes()) throw tooLarge();
                    if(files.find(id).isPresent()) throw new ApiException(HttpStatus.CONFLICT,"该任务标识已存在文件");
                    try {
                        // Recover an object renamed just before a previous process/transaction failed.
                        local.delete(id.toString());
                        long available=limits.localCapacityBytes()-local.usedBytes()-coordinator.reservedUploadBytes(id);
                        if(task.sizeBytes()>available) throw noSpace();
                        parts.publish(assembled,local.path(id.toString()));
                    } catch(IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"文件保存失败，请重试完成上传"); }
                    Instant now=Instant.now();
                    byte[] shareToken=new byte[24];new SecureRandom().nextBytes(shareToken);
                    StoredFile file=new StoredFile(id,task.ownerId(),FileService.safeFilename(task.originalName()),task.contentType(),
                            task.sizeBytes(),id.toString(),Base64.getUrlEncoder().withoutPadding().encodeToString(shareToken),task.permanent(),
                            task.permanent()?null:now.plus(RESULT_LIFETIME),"LOCAL",null,now);
                    files.insert(file);
                    tasks.complete(id,fileService.info(file),now.plus(RESULT_LIFETIME));
                    return status(tasks.find(id));
                });
            } catch(IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"分片合并失败，请重试"); }
            finally {
                if(merged!=null) try { Files.deleteIfExists(merged); }
                catch(IOException ex) { log.warn("Upload assembly cleanup deferred for {}",id); }
            }
        });
        // Do not delete parts until the metadata and COMPLETED marker have both committed.
        clear(id);
        try { events.publishEvent(new StorageChanged()); }
        catch(RuntimeException ex) { log.warn("Upload maintenance notification deferred"); }
        return result;
    }
    public void cancel(UUID id,String token,AppPrincipal principal) {
        String hash=tokenHash(token);
        coordinator.uploadLocked(id,() -> {
            UploadTask task=tasks.find(id);
            if(task==null) {
                tasks.cancelled(id,principal==null?null:principal.id(),hash,Instant.now().plus(RESULT_LIFETIME));
                return null;
            }
            authorize(task,hash,principal);
            if(task.completed()) throw new ApiException(HttpStatus.CONFLICT,"文件已经保存，无法取消上传；请查询任务结果");
            if("UPLOADING".equals(task.state())) tasks.terminal(id,"CANCELLED",Instant.now().plus(RESULT_LIFETIME));
            return null;
        });
        clear(id);
    }
    private UploadTask access(UUID id,String hash,AppPrincipal principal) {
        UploadTask task=tasks.find(id);
        if(task==null) throw notFound();
        authorize(task,hash,principal);requireActive(task);return task;
    }
    private void authorize(UploadTask task,String hash,AppPrincipal principal) {
        if(!MessageDigest.isEqual(task.tokenHash().getBytes(StandardCharsets.US_ASCII),hash.getBytes(StandardCharsets.US_ASCII))
                || task.ownerId()!=null && (principal==null || !task.ownerId().equals(principal.id()))) throw notFound();
    }
    private static void requireUploadAllowed(UUID ownerId,StorageSettings limits) {
        if(ownerId==null && Boolean.FALSE.equals(limits.allowAnonymousUploads()))
            throw new ApiException(HttpStatus.FORBIDDEN,"匿名上传已关闭，请登录后上传文件");
    }
    private void requireActive(UploadTask task) {
        if(!"UPLOADING".equals(task.state()) && !task.completed() || !task.expiresAt().isAfter(Instant.now()))
            throw new ApiException(HttpStatus.GONE,"上传任务已取消或过期，请创建新任务");
    }
    private UploadStatus status(UploadTask task) {
        return status(task,true);
    }
    private UploadStatus status(UploadTask task,boolean reconcile) {
        return status(task,reconcile,() -> {});
    }
    private UploadStatus status(UploadTask task,boolean reconcile,Runnable progress) {
        List<Integer> received;
        if(task.completed()) received=IntStream.range(0,task.totalChunks()).boxed().toList();
        else if(!reconcile) received=tasks.received(task.id());
        else {
            try {
                parts.clearStaging(task.id());
                Set<Integer> recorded=new HashSet<>(tasks.received(task.id()));
                Set<Integer> candidates=new TreeSet<>(recorded);
                candidates.addAll(parts.candidates(task.id()));
                List<Integer> actual=new ArrayList<>();
                for(int index:candidates) {
                    if(index<0 || index>=task.totalChunks()) { parts.removePart(task.id(),index);continue; }
                    // A crash can leave an atomic part rename without its database receipt.
                    boolean present=recorded.contains(index)?parts.present(task,index):parts.valid(task,index,progress);
                    if(present) { actual.add(index);if(!recorded.contains(index)) tasks.received(task,index); }
                    else if(recorded.contains(index)) tasks.forget(task.id(),index);
                }
                received=List.copyOf(actual);
            } catch(IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"无法读取上传进度，请重试"); }
        }
        long uploaded=received.stream().mapToLong(task::chunkBytes).sum();
        return new UploadStatus(task.id(),task.originalName(),task.contentType(),task.sizeBytes(),task.permanent(),
                UploadTask.CHUNK_SIZE,task.totalChunks(),received,uploaded,task.state(),task.expiresAt(),task.file());
    }
    private String tokenHash(String token) {
        if(token==null || !token.matches("[a-f0-9]{64}")) throw new ApiException(HttpStatus.BAD_REQUEST,"上传令牌格式无效");
        return UploadStorage.hash(token.getBytes(StandardCharsets.US_ASCII));
    }
    private void validate(UploadRequest request) {
        if(request==null || request.id()==null || request.originalName()==null || request.originalName().isBlank()
                || request.originalName().length()>255 || request.contentType()==null || request.contentType().isBlank()
                || request.contentType().length()>255 || request.contentType().chars().anyMatch(Character::isISOControl)
                || request.sizeBytes()<1 || request.sizeBytes()>StorageSettings.MAX_UPLOAD_BYTES || request.chunkHashes()==null
                || request.chunkHashes().size()!=(request.sizeBytes()-1)/UploadTask.CHUNK_SIZE+1
                || request.chunkHashes().stream().anyMatch(s -> s==null || !s.matches("[a-f0-9]{64}")))
            throw new ApiException(HttpStatus.BAD_REQUEST,"文件信息或分片 SHA-256 清单无效");
    }
    private ApiException tooLarge() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"文件超过当前单文件上传上限"); }
    private ApiException noSpace() { return new ApiException(HttpStatus.INSUFFICIENT_STORAGE,"本地存储空间不足，请取消其他上传或联系管理员"); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"上传任务不存在"); }
    private Runnable progress(UUID id) {
        renew(id);
        AtomicLong last=new AtomicLong(System.nanoTime());
        return () -> {
            long now=System.nanoTime();
            if(now-last.get()>=Duration.ofSeconds(30).toNanos()) { renew(id);last.set(now); }
        };
    }
    private void renew(UUID id) {
        if(!coordinator.renewUpload(id,ACTIVE_LIFETIME))
            throw new ApiException(HttpStatus.GONE,"上传任务已超过恢复期限，请创建新任务");
    }
    private void clear(UUID id) {
        try { parts.clear(id); }
        catch(IOException ex) { log.warn("Upload parts cleanup deferred for {}",id); }
    }
    @Scheduled(fixedDelayString="${app.maintenance-delay-ms:30000}")
    public void maintain() {
        for(UUID id:tasks.cleanupCandidates()) {
            try {
                coordinator.uploadLocked(id,() -> {
                    UploadTask task=tasks.find(id);
                    if(task!=null && !task.expiresAt().isAfter(Instant.now())) {
                        if("UPLOADING".equals(task.state())) tasks.terminal(id,"EXPIRED",Instant.now().plus(RESULT_LIFETIME));
                        else tasks.delete(id);
                    }
                    return null;
                });
            } catch(RuntimeException ex) { log.warn("Upload expiration cleanup deferred for {}",id); }
        }
        try {
            for(UUID id:parts.directories()) {
                UploadTask candidate=tasks.find(id);
                if(candidate!=null && "UPLOADING".equals(candidate.state()) && candidate.expiresAt().isAfter(Instant.now())) continue;
                coordinator.uploadLocked(id,() -> {
                    UploadTask current=tasks.find(id);
                    if(current==null || !"UPLOADING".equals(current.state())) clear(id);
                    return null;
                });
            }
        } catch(IOException|RuntimeException ex) { log.warn("Upload orphan cleanup will retry"); }
    }
}
