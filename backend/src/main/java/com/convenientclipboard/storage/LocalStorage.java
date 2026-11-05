package com.convenientclipboard.storage;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Component
public class LocalStorage {
    private final Path root;
    public LocalStorage(AppProperties properties) throws IOException {
        root=properties.storagePath().toAbsolutePath().normalize().resolve("objects");
        Files.createDirectories(root);
    }
    public Path path(String key) {
        if(!key.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid object key");
        return root.resolve(key);
    }
    public long write(String key, InputStream input, long limit) throws IOException {
        Path target=path(key);
        long total=0;
        // A failed CREATE_NEW must never delete an object that existed before this write.
        var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        try(output) {
            byte[] buffer=new byte[65536];
            int count;
            while((count=input.read(buffer))!=-1) {
                total+=count;
                if(total>limit) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"文件超过上传或存储容量限制");
                output.write(buffer,0,count);
            }
        } catch(IOException|RuntimeException ex) { Files.deleteIfExists(target); throw ex; }
        return total;
    }
    public InputStream open(String key) throws IOException { return Files.newInputStream(path(key)); }
    public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }
    public List<Path> objects() throws IOException { try(var files=Files.list(root)) { return files.filter(Files::isRegularFile).toList(); } }
    public long usedBytes() {
        try {
            long bytes=0;
            for(Path file:objects()) {
                try { bytes=Math.addExact(bytes,Files.size(file)); }
                catch(java.nio.file.NoSuchFileException ignored) { /* A committed migration removed its local duplicate. */ }
            }
            return bytes;
        }
        catch(IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"无法读取本地存储容量"); }
    }
    public long usableBytes() {
        try { return Files.getFileStore(root).getUsableSpace(); }
        catch(IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"无法读取磁盘可用空间"); }
    }
}
