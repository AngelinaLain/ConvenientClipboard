package com.convenientclipboard.admin;

import com.convenientclipboard.common.ApiExceptionHandler;
import com.convenientclipboard.files.FileInfo;
import com.convenientclipboard.files.FileRepository;
import com.convenientclipboard.files.FileService;
import com.convenientclipboard.files.StoredFile;
import com.convenientclipboard.storage.LocalStorage;
import com.convenientclipboard.storage.ProviderService;
import com.convenientclipboard.storage.SettingsService;
import com.convenientclipboard.storage.StorageMaintenance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminFilesControllerTest {
    FileRepository files;
    FileService fileService;
    MockMvc mvc;

    @BeforeEach void setUp() {
        files = mock(FileRepository.class);
        fileService = mock(FileService.class);
        var controller = new AdminController(files, fileService, mock(LocalStorage.class), mock(SettingsService.class),
                mock(ProviderService.class), mock(StorageMaintenance.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void unfilteredRequestKeepsLegacyArrayAndFileFields() throws Exception {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var file = new StoredFile(UUID.randomUUID(), null, "report.txt", "text/plain", 7,
                UUID.randomUUID().toString(), "a".repeat(32), false, now.plusSeconds(604800), "LOCAL", null, now);
        when(files.all()).thenReturn(List.of(file));
        when(fileService.info(file)).thenReturn(new FileInfo(file.id(), file.originalName(), file.sizeBytes(),
                file.contentType(), file.permanent(), file.expiresAt(), file.createdAt(), file.location(), "https://files.test/s/token"));

        mvc.perform(get("/api/admin/files")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(file.id().toString()))
                .andExpect(jsonPath("$[0].originalName").value("report.txt"))
                .andExpect(jsonPath("$[0].location").value("LOCAL"))
                .andExpect(jsonPath("$[0].shareUrl").value("https://files.test/s/token"));
        verify(files).all();
        verify(files).ownerName(null);
        verifyNoMoreInteractions(files);
    }

    @Test void searchAloneDefaultsToPaginationAndStripsWhitespace() throws Exception {
        when(files.adminCount("report", "", "")).thenReturn(37L);
        mvc.perform(get("/api/admin/files").param("query", "  report  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(37));
        verify(files).adminPage(0, 20, "report", "", "");
        verify(files).adminCount("report", "", "");
        verifyNoMoreInteractions(files);
    }

    @ParameterizedTest
    @CsvSource({"LOCAL,PERMANENT", "S3,TEMPORARY", "NFS,PERMANENT", "SMB,TEMPORARY"})
    void combinedFiltersApplyToBothPageAndCount(String location, String retention) throws Exception {
        mvc.perform(get("/api/admin/files").param("page", "2").param("size", "10").param("query", "  ALICE  ")
                        .param("location", location).param("retention", retention))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.size").value(10));
        verify(files).adminPage(2, 10, "ALICE", location, retention);
        verify(files).adminCount("ALICE", location, retention);
        verifyNoMoreInteractions(files);
    }

    @ParameterizedTest
    @CsvSource({"location,LOCAL", "location,S3", "location,NFS", "location,SMB", "retention,PERMANENT", "retention,TEMPORARY"})
    void filterAloneReturnsDefaultPage(String name, String value) throws Exception {
        String location = name.equals("location") ? value : "";
        String retention = name.equals("retention") ? value : "";
        mvc.perform(get("/api/admin/files").param(name, value))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20));
        verify(files).adminPage(0, 20, "", location, retention);
        verify(files).adminCount("", location, retention);
        verifyNoMoreInteractions(files);
    }

    @Test void explicitPaginationWithBlankFiltersStillReturnsPage() throws Exception {
        mvc.perform(get("/api/admin/files").param("page", "0").param("query", " ")
                        .param("location", " ").param("retention", " "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        verify(files).adminPage(0, 20, "", "", "");
        verify(files).adminCount("", "", "");
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(Arguments.of("page", "-1"), Arguments.of("page", "abc"),
                Arguments.of("size", "0"), Arguments.of("size", "101"), Arguments.of("size", "2147483648"),
                Arguments.of("query", "x".repeat(201)), Arguments.of("query", " ".repeat(201)),
                Arguments.of("location", "REMOTE"), Arguments.of("location", "local"),
                Arguments.of("location", "LOCAL' OR true --"), Arguments.of("retention", "EXPIRED"),
                Arguments.of("retention", "temporary"));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void invalidParametersReturn400BeforeRepositoryAccess(String name, String value) throws Exception {
        mvc.perform(get("/api/admin/files").param(name, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(files, fileService);
    }
}
