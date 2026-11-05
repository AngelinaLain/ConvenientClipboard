package com.convenientclipboard.uploads;

import com.convenientclipboard.auth.AppPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadService service;
    public UploadController(UploadService service) { this.service=service; }
    private AppPrincipal principal(Authentication authentication) {
        return authentication!=null && authentication.getPrincipal() instanceof AppPrincipal p?p:null;
    }
    @PostMapping(consumes="application/json")
    public UploadStatus create(@Valid @RequestBody UploadRequest request,@RequestHeader("X-Upload-Token") String token,Authentication authentication) {
        return service.create(request,token,principal(authentication));
    }
    @GetMapping("/{id}")
    public UploadStatus get(@PathVariable UUID id,@RequestHeader("X-Upload-Token") String token,Authentication authentication) {
        return service.get(id,token,principal(authentication));
    }
    @PutMapping(value="/{id}/chunks/{index}",consumes="application/octet-stream")
    public UploadStatus chunk(@PathVariable UUID id,@PathVariable int index,@RequestHeader("X-Upload-Token") String token,
                              Authentication authentication,HttpServletRequest request) throws IOException {
        return service.chunk(id,index,token,principal(authentication),request.getInputStream(),request.getContentLengthLong());
    }
    @PostMapping("/{id}/complete")
    public UploadStatus complete(@PathVariable UUID id,@RequestHeader("X-Upload-Token") String token,Authentication authentication) {
        return service.complete(id,token,principal(authentication));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id,@RequestHeader("X-Upload-Token") String token,Authentication authentication) {
        service.cancel(id,token,principal(authentication));return ResponseEntity.noContent().build();
    }
}
