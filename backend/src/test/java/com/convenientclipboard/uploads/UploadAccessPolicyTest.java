package com.convenientclipboard.uploads;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.files.FileInfo;
import com.convenientclipboard.files.FileRepository;
import com.convenientclipboard.files.FileService;
import com.convenientclipboard.storage.LocalStorage;
import com.convenientclipboard.storage.SettingsService;
import com.convenientclipboard.storage.StorageCoordinator;
import com.convenientclipboard.storage.StorageSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadAccessPolicyTest {
    private static final String TOKEN = "a".repeat(64);
    private static final long MIB = 1048576L;
    private final UploadRepository tasks = mock(UploadRepository.class);
    private final UploadStorage parts = mock(UploadStorage.class);
    private final FileRepository files = mock(FileRepository.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final StorageCoordinator coordinator = mock(StorageCoordinator.class);
    private final LocalStorage local = mock(LocalStorage.class);
    private final AppPrincipal user = new AppPrincipal(UUID.randomUUID(), "member", "USER");
    private final UUID id = UUID.randomUUID();
    private UploadService service;

    @BeforeEach void setup() {
        when(coordinator.locked(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        when(coordinator.uploadLocked(any(), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        when(coordinator.renewUpload(any(), any())).thenReturn(true);
        when(local.usableBytes()).thenReturn(16 * MIB);
        when(settings.get()).thenReturn(new StorageSettings(16 * MIB, 50, MIB, false, true));
        when(files.find(any())).thenReturn(Optional.empty());
        service = new UploadService(tasks, parts, files, mock(FileService.class), local, settings,
                coordinator, mock(ApplicationEventPublisher.class));
    }

    private UploadRequest request() {
        return new UploadRequest(id, "sample.txt", "text/plain", 1, false, List.of("b".repeat(64)));
    }

    private UploadTask task(UUID ownerId, String state, FileInfo file) {
        UploadRequest request = request();
        return new UploadTask(id, ownerId, UploadStorage.hash(TOKEN.getBytes(StandardCharsets.US_ASCII)),
                request.originalName(), request.contentType(), request.sizeBytes(), request.permanent(),
                request.chunkHashes(), state, Instant.now().plusSeconds(600), file);
    }

    private void assertForbidden(Runnable operation) {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, operation::run).status());
    }

    @Test void disabledAnonymousUploadsRejectNewTasksBeforeReservingSpace() {
        assertForbidden(() -> service.create(request(), TOKEN, null));
        verify(tasks, never()).insert(any());
        verify(coordinator, never()).reservedUploadBytes(any());
        verifyNoInteractions(parts);
    }

    @Test void disabledAnonymousUploadsPreventRenewingExistingGuestTaskEvenAfterLogin() {
        when(tasks.find(id)).thenReturn(task(null, "UPLOADING", null));
        assertForbidden(() -> service.create(request(), TOKEN, null));
        assertForbidden(() -> service.create(request(), TOKEN, user));
        verify(tasks, never()).touch(any(), any());
        verify(tasks, never()).insert(any());
    }

    @Test void disabledAnonymousUploadsPreventChunkAndCompletionForGuestOwnedTasksEvenAfterLogin() throws Exception {
        when(tasks.find(id)).thenReturn(task(null, "UPLOADING", null));
        for (AppPrincipal principal : new AppPrincipal[]{null, user}) {
            assertForbidden(() -> service.chunk(id, 0, TOKEN, principal, new ByteArrayInputStream(new byte[]{1}), 1));
            assertForbidden(() -> service.complete(id, TOKEN, principal));
        }
        verifyNoInteractions(parts);
        verify(tasks, never()).received(any(), anyInt());
        verify(tasks, never()).complete(any(), any(), any());
        verify(files, never()).insert(any());
    }

    @Test void guestCanStillReadProgressAndCancelBlockedTask() throws Exception {
        when(tasks.find(id)).thenReturn(task(null, "UPLOADING", null));
        assertEquals("UPLOADING", service.get(id, TOKEN, null).state());
        service.cancel(id, TOKEN, null);
        verify(tasks).terminal(eq(id), eq("CANCELLED"), any());
        verify(parts).clear(id);
    }

    @Test void completedGuestResultsRemainAvailableWhenAnonymousUploadsAreDisabled() {
        FileInfo file = new FileInfo(id, "sample.txt", 1, "text/plain", false, Instant.now().plusSeconds(600),
                Instant.now(), "LOCAL", "https://files.example/s/" + "a".repeat(32));
        when(tasks.find(id)).thenReturn(task(null, "COMPLETED", file));
        assertEquals(file, service.get(id, TOKEN, null).file());
        assertEquals(file, service.create(request(), TOKEN, null).file());
        assertEquals(file, service.complete(id, TOKEN, null).file());
        verify(files, never()).insert(any());
    }

    @Test void authenticatedUsersCanCreateAndContinueTheirOwnUploads() throws Exception {
        UploadStatus created = service.create(request(), TOKEN, user);
        assertEquals(id, created.id());
        var inserted = org.mockito.ArgumentCaptor.forClass(UploadTask.class);
        verify(tasks).insert(inserted.capture());
        assertEquals(user.id(), inserted.getValue().ownerId());

        UploadTask ownedTask = task(user.id(), "UPLOADING", null);
        when(tasks.find(id)).thenReturn(ownedTask);
        var input = new ByteArrayInputStream(new byte[]{1});
        assertEquals("UPLOADING", service.chunk(id, 0, TOKEN, user, input, 1).state());
        verify(parts).receive(eq(ownedTask), eq(0), same(input), eq(false), any());
        verify(tasks).received(ownedTask, 0);
    }
}
