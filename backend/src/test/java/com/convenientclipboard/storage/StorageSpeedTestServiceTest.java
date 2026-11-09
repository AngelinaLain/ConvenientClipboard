package com.convenientclipboard.storage;

import com.convenientclipboard.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageSpeedTestServiceTest {
    @TempDir Path root;
    final ProviderService providers = mock(ProviderService.class);
    final RemoteStorage remote = mock(RemoteStorage.class);
    final StorageProvider provider = new StorageProvider(UUID.randomUUID(), "remote", "https://storage.test",
            "us-east-1", "bucket", "encrypted-access", "encrypted-secret", false, 100);
    StorageSpeedTestService service;

    @AfterEach void stop() { if (service != null) service.shutdown(); }

    private void setup() {
        when(providers.get(provider.id())).thenReturn(provider);
        service = new StorageSpeedTestService(providers, remote, root.resolve("samples"));
    }

    private AtomicReference<Path> uploadSource() {
        AtomicReference<Path> sample = new AtomicReference<>();
        doAnswer(call -> { sample.set(call.getArgument(2)); return null; })
                .when(remote).uploadVerified(eq(provider), anyString(), any(), eq(StorageSpeedTestService.SAMPLE_BYTES));
        return sample;
    }

    @Test void transfersVerifiedSampleBothWaysForDisabledProviderAndCleansOnlyItsObjects() throws Exception {
        setup();
        var sample = uploadSource();
        when(remote.open(eq(provider), anyString())).thenAnswer(call -> Files.newInputStream(sample.get()));
        var started = service.start(provider.id());
        assertEquals(StorageSpeedTestService.Status.PREPARING, started.status());
        assertNull(started.uploadBytesPerSecond());
        var result = await(started);
        assertEquals(StorageSpeedTestService.Status.COMPLETED, result.status());
        assertTrue(Double.isFinite(result.uploadBytesPerSecond()) && result.uploadBytesPerSecond() > 0);
        assertTrue(Double.isFinite(result.downloadBytesPerSecond()) && result.downloadBytesPerSecond() > 0);
        assertTrue(result.uploadDurationMillis() >= 1);
        assertTrue(result.downloadDurationMillis() >= 1);
        assertNotNull(result.finishedAt());
        assertNull(result.error());
        assertNull(result.warning());
        assertFalse(Files.exists(sample.get()));
        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(remote).uploadVerified(eq(provider), key.capture(), any(), eq(StorageSpeedTestService.SAMPLE_BYTES));
        assertDoesNotThrow(() -> UUID.fromString(key.getValue()));
        var order = inOrder(remote);
        order.verify(remote).uploadVerified(eq(provider), eq(key.getValue()), any(), anyLong());
        order.verify(remote).open(provider, key.getValue());
        order.verify(remote).delete(provider, key.getValue());
        verify(providers, times(1)).get(provider.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NFS", "SMB"})
    void measuresMountedAdapterAndLeavesExistingShareFilesUntouched(String type) throws Exception {
        Path share = Files.createDirectory(root.resolve("share"));
        Files.writeString(share.resolve(MountedStorage.MARKER), "test-share");
        Path existing = share.resolve(UUID.randomUUID().toString());
        Files.writeString(existing, "existing user file");
        var mounted = new StorageProvider(provider.id(), type, null, null, null, null, null, true, 100,
                type, share.toString(), "test-share");
        when(providers.get(provider.id())).thenReturn(mounted);
        var adapter = new RemoteStorage(mock(CredentialCipher.class), new MountedStorage(root.toString()));
        service = new StorageSpeedTestService(providers, adapter, root.resolve("samples"));
        var result = await(service.start(provider.id()));
        assertEquals(StorageSpeedTestService.Status.COMPLETED, result.status());
        assertNotNull(result.uploadBytesPerSecond());
        assertNotNull(result.downloadBytesPerSecond());
        assertEquals("existing user file", Files.readString(existing));
        try (var entries = Files.list(share)) { assertEquals(2, entries.count()); }
        try (var entries = Files.list(root.resolve("samples"))) { assertEquals(0, entries.count()); }
    }

    @Test void rejectsConcurrentTestsAndKeepsCleanupInTheActivePeriod() throws Exception {
        setup();
        var sample = uploadSource();
        when(remote.open(eq(provider), anyString())).thenAnswer(call -> Files.newInputStream(sample.get()));
        CountDownLatch cleaning = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            cleaning.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test latch timeout");
            return null;
        }).when(remote).delete(eq(provider), anyString());
        var started = service.start(provider.id());
        try {
            assertTrue(cleaning.await(10, TimeUnit.SECONDS));
            assertEquals(StorageSpeedTestService.Status.CLEANING, service.get(provider.id(), started.id()).status());
            ApiException busy = assertThrows(ApiException.class, () -> service.start(UUID.randomUUID()));
            assertEquals(HttpStatus.CONFLICT, busy.status());
            verify(providers, times(1)).get(any());
        } finally { release.countDown(); }
        assertEquals(StorageSpeedTestService.Status.COMPLETED, await(started).status());
    }

    @Test void failedUploadStillAttemptsRemoteDeletionAndDoesNotExposeDriverSecrets() throws Exception {
        setup();
        AtomicReference<Path> source = new AtomicReference<>();
        doAnswer(call -> { source.set(call.getArgument(2)); throw new IllegalStateException("secret-access-key"); })
                .when(remote).uploadVerified(eq(provider), anyString(), any(), anyLong());
        var result = await(service.start(provider.id()));
        assertEquals(StorageSpeedTestService.Status.FAILED, result.status());
        assertTrue(result.error().contains("上传测速失败"));
        assertFalse(result.error().contains("secret-access-key"));
        assertNull(result.uploadBytesPerSecond());
        assertNull(result.downloadBytesPerSecond());
        verify(remote).delete(eq(provider), anyString());
        verify(remote, never()).open(any(), anyString());
        assertFalse(Files.exists(source.get()));
    }

    @Test void corruptedDownloadRetainsUploadResultAndRejectsDownloadRate() throws Exception {
        setup();
        var sample = uploadSource();
        when(remote.open(eq(provider), anyString())).thenAnswer(call -> new FilterInputStream(Files.newInputStream(sample.get())) {
            boolean first = true;
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length);
                if (count > 0 && first) { buffer[offset] ^= 1; first = false; }
                return count;
            }
        });
        var result = await(service.start(provider.id()));
        assertEquals(StorageSpeedTestService.Status.FAILED, result.status());
        assertTrue(result.error().contains("校验"));
        assertNotNull(result.uploadBytesPerSecond());
        assertNull(result.downloadBytesPerSecond());
        verify(remote).delete(eq(provider), anyString());
        assertFalse(Files.exists(sample.get()));
    }

    @Test void shortDownloadFailsAndClosesStream() throws Exception {
        setup();
        uploadSource();
        InputStream input = spy(new ByteArrayInputStream(new byte[1024]));
        when(remote.open(eq(provider), anyString())).thenReturn(input);
        var result = await(service.start(provider.id()));
        assertEquals(StorageSpeedTestService.Status.FAILED, result.status());
        assertNotNull(result.uploadBytesPerSecond());
        assertNull(result.downloadBytesPerSecond());
        verify(input).close();
        verify(remote).delete(eq(provider), anyString());
    }

    @Test void oversizedDownloadIsBoundedEvenIfRemoteNeverEnds() throws Exception {
        setup();
        uploadSource();
        AtomicLong read = new AtomicLong();
        InputStream infinite = new InputStream() {
            @Override public int read() { read.incrementAndGet(); return 0; }
            @Override public int read(byte[] bytes, int offset, int length) {
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 0);
                read.addAndGet(length);
                return length;
            }
        };
        when(remote.open(eq(provider), anyString())).thenReturn(infinite);
        var result = await(service.start(provider.id()));
        assertEquals(StorageSpeedTestService.Status.FAILED, result.status());
        assertEquals(StorageSpeedTestService.SAMPLE_BYTES + 1, read.get());
        assertNull(result.downloadBytesPerSecond());
    }

    @Test void cleanupFailureReportsExactObjectWithoutExposingDriverErrorOrLosingMeasurements() throws Exception {
        setup();
        var sample = uploadSource();
        when(remote.open(eq(provider), anyString())).thenAnswer(call -> Files.newInputStream(sample.get()));
        doThrow(new IOException("secret-access-key")).when(remote).delete(eq(provider), anyString());
        var result = await(service.start(provider.id()));
        assertEquals(StorageSpeedTestService.Status.COMPLETED, result.status());
        assertNotNull(result.downloadBytesPerSecond());
        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(remote).delete(eq(provider), key.capture());
        assertTrue(result.warning().contains(key.getValue()));
        assertFalse(result.warning().contains("secret-access-key"));
        assertFalse(Files.exists(sample.get()));
    }

    @Test void failedPreparationReleasesSlotAndOldHistoryIsBounded() throws Exception {
        when(providers.get(provider.id())).thenReturn(provider);
        Path blockedRoot = Files.writeString(root.resolve("not-a-directory"), "blocked");
        service = new StorageSpeedTestService(providers, remote, blockedRoot);
        var first = service.start(provider.id());
        assertEquals(StorageSpeedTestService.Status.FAILED, await(first).status());
        StorageSpeedTestService.SpeedTest last = first;
        for (int count = 0; count < 20; count++) last = await(service.start(provider.id()));
        assertEquals(StorageSpeedTestService.Status.FAILED, last.status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.get(provider.id(), first.id())).status());
        assertEquals(last, service.get(provider.id(), last.id()));
        verifyNoInteractions(remote);
    }

    @Test void unknownProviderAndWrongProviderJobAreNotFound() throws Exception {
        setup();
        UUID unknown = UUID.randomUUID();
        when(providers.get(unknown)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "存储器不存在"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.start(unknown)).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.get(provider.id(), UUID.randomUUID())).status());
        var sample = uploadSource();
        when(remote.open(eq(provider), anyString())).thenAnswer(call -> Files.newInputStream(sample.get()));
        var result = await(service.start(provider.id()));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.get(unknown, result.id())).status());
    }

    private StorageSpeedTestService.SpeedTest await(StorageSpeedTestService.SpeedTest initial) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            var result = service.get(initial.providerId(), initial.id());
            if (result.status() == StorageSpeedTestService.Status.COMPLETED || result.status() == StorageSpeedTestService.Status.FAILED)
                return result;
            Thread.sleep(10);
        }
        fail("Speed test did not terminate");
        return null;
    }
}
