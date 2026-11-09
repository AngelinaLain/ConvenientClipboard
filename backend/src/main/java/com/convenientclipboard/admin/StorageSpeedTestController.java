package com.convenientclipboard.admin;

import com.convenientclipboard.storage.StorageSpeedTestService;
import com.convenientclipboard.storage.StorageSpeedTestService.SpeedTest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/providers/{providerId}/speed-test")
public class StorageSpeedTestController {
    private final StorageSpeedTestService tests;

    public StorageSpeedTestController(StorageSpeedTestService tests) {
        this.tests = tests;
    }

    @PostMapping
    public ResponseEntity<SpeedTest> start(@PathVariable UUID providerId) {
        return ResponseEntity.accepted().body(tests.start(providerId));
    }

    @GetMapping("/{testId}")
    public SpeedTest get(@PathVariable UUID providerId, @PathVariable UUID testId) {
        return tests.get(providerId, testId);
    }
}
