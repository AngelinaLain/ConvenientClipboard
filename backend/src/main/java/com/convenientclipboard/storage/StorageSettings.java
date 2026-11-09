package com.convenientclipboard.storage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record StorageSettings(@Min(1048576) long localCapacityBytes, @Min(10) @Max(90) int offloadThreshold,
                              @Min(1048576) @Max(107374182400L) Long maxUploadBytes,
                              Boolean allowAnonymousUploads, Boolean registrationEnabled) {
    public static final long MIN_UPLOAD_BYTES=1048576L;
    public static final long MAX_UPLOAD_BYTES=107374182400L;
    /** Omitted settings are resolved to their current database values by save(). */
    public StorageSettings(long localCapacityBytes,int offloadThreshold) { this(localCapacityBytes,offloadThreshold,null,null,null); }
    public StorageSettings(long localCapacityBytes,int offloadThreshold,Long maxUploadBytes) { this(localCapacityBytes,offloadThreshold,maxUploadBytes,null,null); }
}
