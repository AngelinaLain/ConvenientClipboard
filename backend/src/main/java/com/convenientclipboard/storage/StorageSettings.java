package com.convenientclipboard.storage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record StorageSettings(@Min(1048576) long localCapacityBytes, @Min(10) @Max(90) int offloadThreshold,
                              @Min(1048576) @Max(107374182400L) Long maxUploadBytes) {
    public static final long MIN_UPLOAD_BYTES=1048576L;
    public static final long MAX_UPLOAD_BYTES=107374182400L;
    /** Older settings requests omit the upload limit; save() resolves this to the current value. */
    public StorageSettings(long localCapacityBytes,int offloadThreshold) { this(localCapacityBytes,offloadThreshold,null); }
}
