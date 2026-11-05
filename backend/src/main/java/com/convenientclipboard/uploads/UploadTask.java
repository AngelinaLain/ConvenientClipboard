package com.convenientclipboard.uploads;

import com.convenientclipboard.files.FileInfo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record UploadTask(UUID id, UUID ownerId, String tokenHash, String originalName, String contentType, long sizeBytes,
                  boolean permanent, List<String> chunkHashes, String state, Instant expiresAt, FileInfo file) {
    static final int CHUNK_SIZE = 8 * 1024 * 1024;
    int totalChunks() { return chunkHashes.size(); }
    long chunkBytes(int index) { return Math.min(CHUNK_SIZE, sizeBytes - (long) index * CHUNK_SIZE); }
    boolean completed() { return "COMPLETED".equals(state); }
}
