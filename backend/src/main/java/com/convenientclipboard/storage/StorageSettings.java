package com.convenientclipboard.storage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record StorageSettings(@Min(1048576) long localCapacityBytes, @Min(10) @Max(90) int offloadThreshold) {}
