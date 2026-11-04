package com.convenientclipboard.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** NFS/SMB are mounted by the host; the application never mounts shares or receives NAS credentials. */
@Component
public class MountedStorage {
    public static final String MARKER=".clipboard-storage-id";
    private final Path allowedRoot;
    public MountedStorage(@Value("${app.mounted-storage-root:${APP_MOUNTED_STORAGE_ROOT:/mnt/remote}}") String root) {
        allowedRoot=Path.of(root).toAbsolutePath().normalize();
    }
    public Path validate(StorageProvider provider) throws IOException {
        if(!provider.mounted() || provider.mountPath()==null || provider.mountId()==null || !provider.mountId().matches("[A-Za-z0-9_-]{1,128}"))
            throw new IOException("NFS/SMB 需要有效的挂载路径和挂载标识");
        final Path requested;
        try { requested=Path.of(provider.mountPath()); }
        catch(java.nio.file.InvalidPathException ex) { throw new IOException("挂载路径无效"); }
        if(!requested.isAbsolute() || !requested.normalize().startsWith(allowedRoot)) throw new IOException("挂载路径必须位于配置的挂载根目录内");
        for(Path segment:requested) if(segment.toString().equals("..")) throw new IOException("挂载路径不能包含上级目录跳转");
        Path root=requested.normalize();
        // Reject symlinks in every directory component, including the configured root's parents.
        for(Path cursor=root;cursor!=null;cursor=cursor.getParent()) {
            if(Files.isSymbolicLink(cursor)) throw new IOException("挂载路径不能包含符号链接");
        }
        if(!Files.isDirectory(allowedRoot,LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("挂载目录不存在，请先在宿主机或 Docker 配置挂载");
        if(!root.toRealPath().startsWith(allowedRoot.toRealPath())) throw new IOException("挂载真实路径超出配置的根目录");
        if(!Files.isReadable(root) || !Files.isWritable(root)) throw new IOException("挂载目录必须可读写");
        Path marker=root.resolve(MARKER);
        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)) throw new IOException("挂载标识文件不存在；为避免写入未挂载的本地目录，操作已拒绝");
        byte[] bytes;
        try(InputStream input=Files.newInputStream(marker,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(257); }
        if(bytes.length>256 || !new String(bytes,StandardCharsets.UTF_8).strip().equals(provider.mountId())) throw new IOException("挂载标识不匹配，存储不可用");
        return root;
    }
    private Path object(Path root,String key) throws IOException {
        if(!key.matches("[a-f0-9-]{36}")) throw new IOException("无效的对象键");
        Path target=root.resolve(key);
        if(Files.isSymbolicLink(target)) throw new IOException("对象路径不能是符号链接");
        return target;
    }
    public void uploadVerified(StorageProvider provider,String key,Path source,long expectedSize) throws IOException {
        Path root=validate(provider);
        Path target=object(root,key);
        Path temporary=root.resolve(".upload-"+UUID.randomUUID());
        try {
            Files.copy(source,temporary);
            if(Files.size(temporary)!=expectedSize) throw new IOException("挂载存储写入大小校验失败");
            validate(provider);
            object(root,key);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            validate(provider);
            if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.size(target)!=expectedSize) throw new IOException("挂载存储对象校验失败");
        } finally {
            // An unmounted path must not be used even for cleanup.
            try { validate(provider);Files.deleteIfExists(temporary); }
            catch(IOException ignored) { /* Preserve the original failure; the local source remains authoritative. */ }
        }
    }
    public InputStream open(StorageProvider provider,String key) throws IOException {
        Path target=object(validate(provider),key);
        if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("挂载存储对象不可用");
        return Files.newInputStream(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
    }
    public void delete(StorageProvider provider,String key) throws IOException { Files.deleteIfExists(object(validate(provider),key)); }
}
