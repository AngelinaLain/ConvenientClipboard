package com.convenientclipboard.uploads;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Files are published atomically before their receipt is committed to PostgreSQL. */
@Component
public class UploadStorage {
    private final Path root;
    public UploadStorage(AppProperties properties) throws IOException {
        root=properties.storagePath().toAbsolutePath().normalize().resolve("uploads");
        Files.createDirectories(root);
    }
    Path directory(UUID id) { return root.resolve(id.toString()); }
    Path part(UUID id,int index) { return directory(id).resolve(index+".part"); }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    boolean present(UploadTask task,int index) throws IOException {
        Path file=part(task.id(),index);
        return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && Files.size(file)==task.chunkBytes(index);
    }
    boolean valid(UploadTask task,int index,Runnable progress) throws IOException {
        if(!present(task,index)) return false;
        MessageDigest digest=digest();
        try(InputStream input=Files.newInputStream(part(task.id(),index))) {
            byte[] buffer=new byte[65536]; int count;
            while((count=input.read(buffer))!=-1) { digest.update(buffer,0,count);progress.run(); }
        }
        return HexFormat.of().formatHex(digest.digest()).equals(task.chunkHashes().get(index));
    }
    void receive(UploadTask task,int index,InputStream input,boolean discard,Runnable progress) throws IOException {
        Path temporary=null;
        if(!discard) {
            Files.createDirectories(directory(task.id()));
            temporary=Files.createTempFile(directory(task.id()),".incoming-",".tmp");
        }
        try {
            if(discard) readChunk(task,index,input,null,progress);
            else try(FileChannel output=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                readChunk(task,index,input,output,progress);
                output.force(true);
            }
            if(!discard) Files.move(temporary,part(task.id(),index),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { if(temporary!=null) Files.deleteIfExists(temporary); }
    }
    private void readChunk(UploadTask task,int index,InputStream input,FileChannel output,Runnable progress) throws IOException {
        MessageDigest digest=digest();
        long expected=task.chunkBytes(index), total=0;
        byte[] buffer=new byte[65536];
        var stream=output==null?null:Channels.newOutputStream(output);
        while(true) {
            int count=input.read(buffer,0,(int)Math.min(buffer.length,expected+1-total));
            if(count==-1) break;
            if(count==0) continue;
            total+=count;
            if(total>expected) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"分片超过预期大小");
            digest.update(buffer,0,count);
            if(stream!=null) stream.write(buffer,0,count);
            progress.run();
        }
        if(total!=expected) throw new ApiException(HttpStatus.BAD_REQUEST,"分片长度与文件清单不符");
        if(!HexFormat.of().formatHex(digest.digest()).equals(task.chunkHashes().get(index)))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"分片 SHA-256 校验失败，请重新选择原文件");
    }
    Path merge(UploadTask task,Runnable progress) throws IOException {
        Files.createDirectories(directory(task.id()));
        Path merged=Files.createTempFile(directory(task.id()),".merge-",".tmp");
        try(FileChannel output=FileChannel.open(merged,StandardOpenOption.WRITE)) {
            for(int index=0;index<task.totalChunks();index++) {
                try(InputStream input=Files.newInputStream(part(task.id(),index))) { readChunk(task,index,input,output,progress); }
                catch(ApiException|NoSuchFileException ex) {
                    Files.deleteIfExists(part(task.id(),index));
                    throw new ApiException(HttpStatus.CONFLICT,"已接收分片不可用，请查询进度后重新上传缺失分片");
                }
            }
            output.force(true);
            return merged;
        } catch(IOException|RuntimeException ex) { Files.deleteIfExists(merged); throw ex; }
    }
    void publish(Path merged,Path target) throws IOException {
        Files.move(merged,target,StandardCopyOption.ATOMIC_MOVE);
    }
    void removePart(UUID id,int index) throws IOException { Files.deleteIfExists(part(id,index)); }
    List<Integer> candidates(UUID id) throws IOException {
        Path directory=directory(id);
        if(!Files.isDirectory(directory)) return List.of();
        try(var paths=Files.list(directory)) {
            return paths.map(p -> p.getFileName().toString()).filter(s -> s.matches("[0-9]{1,5}\\.part"))
                    .map(s -> Integer.parseInt(s.substring(0,s.length()-5))).toList();
        }
    }
    void clearStaging(UUID id) throws IOException {
        Path directory=directory(id);
        if(!Files.isDirectory(directory)) return;
        try(var paths=Files.list(directory)) {
            for(Path path:paths.filter(p -> p.getFileName().toString().startsWith(".")).toList()) Files.deleteIfExists(path);
        }
    }
    List<UUID> directories() throws IOException {
        try(var paths=Files.list(root)) {
            return paths.filter(p -> Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString()).filter(s -> s.matches("[a-f0-9-]{36}"))
                    .map(UUID::fromString).toList();
        }
    }
    void clear(UUID id) throws IOException {
        Path directory=directory(id).normalize();
        if(!directory.startsWith(root) || directory.equals(root)) throw new IOException("Invalid upload directory");
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths=Files.walk(directory)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
