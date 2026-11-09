package com.convenientclipboard.clipboards;

import java.time.Instant;

public record ClipboardInfo(String id, String content, String shareUrl, Instant createdAt, Instant expiresAt) {
}
