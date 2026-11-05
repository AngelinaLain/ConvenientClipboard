package com.convenientclipboard.uploads;

import com.convenientclipboard.files.FileInfo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadStatus(UUID id, String originalName, String contentType, long sizeBytes, boolean permanent,
                           int chunkSize, int totalChunks, List<Integer> receivedChunks, long uploadedBytes,
                           String state, Instant expiresAt, FileInfo file) {}
