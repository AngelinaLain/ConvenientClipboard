package com.convenientclipboard.uploads;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record UploadRequest(@NotNull UUID id, @NotBlank @Size(max=255) String originalName,
                            @NotBlank @Size(max=255) String contentType,
                            @Min(1) @Max(107374182400L) long sizeBytes, boolean permanent,
                            @NotNull @Size(min=1,max=12800) List<@NotNull @Pattern(regexp="[a-f0-9]{64}") String> chunkHashes) {}
