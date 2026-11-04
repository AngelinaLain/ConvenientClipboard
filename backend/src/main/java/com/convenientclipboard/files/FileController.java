package com.convenientclipboard.files;

import com.convenientclipboard.auth.AppPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
public class FileController {
    private final FileService service;
    public FileController(FileService service) { this.service=service; }
    private static AppPrincipal principal(Authentication authentication) { return authentication!=null && authentication.getPrincipal() instanceof AppPrincipal p?p:null; }
    @PostMapping(value="/api/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileInfo upload(@RequestParam MultipartFile file,@RequestParam(defaultValue="false") boolean permanent,Authentication authentication) { return service.upload(file,permanent,principal(authentication)); }
    @GetMapping("/api/files") public List<FileInfo> mine(Authentication authentication) { return service.owned(principal(authentication)); }
    public record RetentionRequest(boolean permanent) {}
    @PatchMapping("/api/files/{id}/retention") public FileInfo retention(@PathVariable UUID id,@RequestBody RetentionRequest request,Authentication authentication) { return service.retention(id,request.permanent(),principal(authentication)); }
    @DeleteMapping("/api/files/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id,Authentication authentication) { service.delete(id,principal(authentication));return ResponseEntity.noContent().build(); }
    @GetMapping("/api/shares/{token}") public FileInfo share(@PathVariable String token) { return service.share(token); }
    @GetMapping("/api/shares/{token}/download") public ResponseEntity<InputStreamResource> download(@PathVariable String token) {
        FileService.Download download=service.download(token);
        String disposition=ContentDisposition.attachment().filename(download.file().originalName(),StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,disposition).header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(download.file().sizeBytes()).body(new InputStreamResource(download.input()));
    }
}
