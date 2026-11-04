package com.convenientclipboard.files;

import java.time.Instant;
import java.util.UUID;

public record FileInfo(UUID id, String originalName, long sizeBytes, String contentType,
                       boolean permanent, Instant expiresAt, Instant createdAt, String location,
                       String shareUrl) {}
