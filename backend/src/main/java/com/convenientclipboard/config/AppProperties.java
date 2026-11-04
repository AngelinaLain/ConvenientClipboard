package com.convenientclipboard.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String publicBaseUrl, Path storagePath, long maxUploadBytes,
                            String jwtSecret, String adminUsername, String adminPassword,
                            String storageEncryptionKey) {
    public AppProperties {
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("APP_JWT_SECRET must contain at least 32 UTF-8 bytes");
        if (storageEncryptionKey == null || storageEncryptionKey.length() < 32)
            throw new IllegalArgumentException("APP_STORAGE_ENCRYPTION_KEY must contain at least 32 characters");
        if (maxUploadBytes < 1) throw new IllegalArgumentException("APP_MAX_UPLOAD_BYTES must be positive");
        if (storagePath == null) throw new IllegalArgumentException("APP_STORAGE_PATH is required");
        if (publicBaseUrl == null || !publicBaseUrl.matches("https?://[^\\s]+"))
            throw new IllegalArgumentException("APP_PUBLIC_BASE_URL must be an HTTP(S) URL");
    }
}
