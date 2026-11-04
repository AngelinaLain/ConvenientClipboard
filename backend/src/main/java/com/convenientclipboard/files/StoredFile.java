package com.convenientclipboard.files;

import java.time.Instant;
import java.util.UUID;

public record StoredFile(UUID id, UUID ownerId, String originalName, String contentType,
                         long sizeBytes, String objectKey, String shareToken, boolean permanent,
                         Instant expiresAt, String location, UUID providerId, Instant createdAt) {
    public boolean expired(Instant now) { return !permanent && expiresAt != null && !expiresAt.isAfter(now); }
}
