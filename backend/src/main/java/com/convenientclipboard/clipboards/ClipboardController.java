package com.convenientclipboard.clipboards;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClipboardController {
    private final ClipboardService service;

    public ClipboardController(ClipboardService service) {
        this.service = service;
    }

    public record CreateRequest(String content, String customId) {
    }

    @PostMapping(value = "/api/clipboards", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClipboardInfo> create(@RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(request.content(), request.customId()));
    }

    @GetMapping("/api/clipboards/{id}")
    public ResponseEntity<ClipboardInfo> get(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }
}
