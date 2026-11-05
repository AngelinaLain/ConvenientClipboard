package com.convenientclipboard.uploads;

import com.convenientclipboard.common.ApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UploadControllerTest {
    UploadService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(UploadService.class);
        mvc=MockMvcBuilders.standaloneSetup(new UploadController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void missingTokenAndMalformedIdentifiersReturn400() throws Exception {
        mvc.perform(get("/api/uploads/"+UUID.randomUUID())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/uploads/not-a-uuid").header("X-Upload-Token","a".repeat(64))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void nullManifestEntriesAreRejectedBeforeServiceAccess() throws Exception {
        mvc.perform(post("/api/uploads").header("X-Upload-Token","a".repeat(64)).contentType("application/json")
                .content("{\"id\":\""+UUID.randomUUID()+"\",\"originalName\":\"x\",\"contentType\":\"text/plain\",\"sizeBytes\":1,\"chunkHashes\":[null]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void rawChunkEndpointRejectsAnUnsupportedContentType() throws Exception {
        mvc.perform(put("/api/uploads/"+UUID.randomUUID()+"/chunks/0").header("X-Upload-Token","a".repeat(64))
                .contentType("text/plain").content("x")).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }
}
