package com.convenientclipboard.storage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProviderRequest(@NotBlank @Size(max=100) String name,
                              @Size(max=2048) String endpoint,
                              @Size(max=80) String region,
                              @Size(max=255) String bucket,
                              @Size(max=1024) String accessKey, @Size(max=2048) String secretKey,
                              boolean enabled, @Min(0) @Max(10000) int priority,
                              @Pattern(regexp="S3|NFS|SMB") String type,
                              @Size(max=2048) String mountPath, @Size(max=128) String mountId) {
    public ProviderRequest { if(type==null || type.isBlank()) type="S3"; }
}
