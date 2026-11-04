package com.convenientclipboard.storage;

import java.util.UUID;

public record StorageProvider(UUID id, String name, String endpoint, String region, String bucket,
                              String accessKeyEncrypted, String secretKeyEncrypted, boolean enabled, int priority,
                              String type, String mountPath, String mountId) {
    public StorageProvider(UUID id,String name,String endpoint,String region,String bucket,String accessKeyEncrypted,String secretKeyEncrypted,boolean enabled,int priority) {
        this(id,name,endpoint,region,bucket,accessKeyEncrypted,secretKeyEncrypted,enabled,priority,"S3",null,null);
    }
    public boolean mounted() { return "NFS".equals(type) || "SMB".equals(type); }
}
