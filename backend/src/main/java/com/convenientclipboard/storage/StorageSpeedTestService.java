package com.convenientclipboard.storage;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Measures the server-to-storage transfer path without creating application file records. */
@Service
public class StorageSpeedTestService {
    public static final long SAMPLE_BYTES = 16L * 1024 * 1024;
    private static final int HISTORY_LIMIT = 20;

    public enum Status { PREPARING, UPLOADING, DOWNLOADING, CLEANING, COMPLETED, FAILED }

    public record SpeedTest(UUID id, UUID providerId, Status status, long sampleBytes,
                            Double uploadBytesPerSecond, Double downloadBytesPerSecond,
                            Long uploadDurationMillis, Long downloadDurationMillis,
                            Instant startedAt, Instant finishedAt, String error, String warning) {}

    private static final class Job {
        final StorageProvider provider;
        volatile SpeedTest snapshot;
        Job(StorageProvider provider) {
            this.provider = provider;
            snapshot = new SpeedTest(UUID.randomUUID(), provider.id(), Status.PREPARING, SAMPLE_BYTES,
                    null, null, null, null, Instant.now(), null, null, null);
        }
    }

    private final ProviderService providers;
    private final RemoteStorage remote;
    private final Path temporaryRoot;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofVirtual().name("storage-speed-test-", 0).factory());
    private final LinkedHashMap<UUID, Job> history = new LinkedHashMap<>();
    private boolean running;

    @Autowired
    public StorageSpeedTestService(ProviderService providers, RemoteStorage remote, AppProperties properties) {
        this(providers, remote, properties.storagePath().toAbsolutePath().normalize().resolve("speed-tests"));
    }

    StorageSpeedTestService(ProviderService providers, RemoteStorage remote, Path temporaryRoot) {
        this.providers = providers;
        this.remote = remote;
        this.temporaryRoot = temporaryRoot;
    }

    public synchronized SpeedTest start(UUID providerId) {
        if (running) throw new ApiException(HttpStatus.CONFLICT, "已有远端存储测速正在进行，请稍后重试");
        // Keep an immutable configuration snapshot, including for deletion after a configuration change.
        Job job = new Job(providers.get(providerId));
        SpeedTest initial = job.snapshot;
        running = true;
        history.put(initial.id(), job);
        while (history.size() > HISTORY_LIMIT) history.remove(history.keySet().iterator().next());
        try {
            worker.execute(() -> run(job));
        } catch (RejectedExecutionException ex) {
            running = false;
            history.remove(initial.id());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "测速服务暂时不可用，请稍后重试");
        }
        return initial;
    }

    public synchronized SpeedTest get(UUID providerId, UUID testId) {
        Job job = history.get(testId);
        if (job == null || !job.provider.id().equals(providerId))
            throw new ApiException(HttpStatus.NOT_FOUND, "测速任务不存在或已过期，请重新测速");
        return job.snapshot;
    }

    private void run(Job job) {
        Path sample = null;
        String objectKey = UUID.randomUUID().toString();
        boolean remoteAttempted = false;
        Double uploadRate = null, downloadRate = null;
        Long uploadMillis = null, downloadMillis = null;
        String error = null, warning = null;
        Status phase = Status.PREPARING;
        try {
            Files.createDirectories(temporaryRoot);
            sample = Files.createTempFile(temporaryRoot, "speed-", ".bin");
            byte[] expectedHash = prepare(sample);
            phase = Status.UPLOADING;
            publish(job, phase, null, null, null, null, null, null);
            remoteAttempted = true;
            long start = System.nanoTime();
            remote.uploadVerified(job.provider, objectKey, sample, SAMPLE_BYTES);
            long elapsed = Math.max(1, System.nanoTime() - start);
            uploadRate = SAMPLE_BYTES * 1_000_000_000.0 / elapsed;
            uploadMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(elapsed));
            phase = Status.DOWNLOADING;
            publish(job, phase, uploadRate, null, uploadMillis, null, null, null);
            start = System.nanoTime();
            byte[] receivedHash;
            try (InputStream input = remote.open(job.provider, objectKey)) {
                receivedHash = downloadHash(input);
            }
            elapsed = Math.max(1, System.nanoTime() - start);
            if (!MessageDigest.isEqual(expectedHash, receivedHash))
                throw new IOException("Speed test checksum mismatch");
            downloadRate = SAMPLE_BYTES * 1_000_000_000.0 / elapsed;
            downloadMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(elapsed));
        } catch (Exception ex) {
            // Driver errors may contain endpoint URLs or credentials. Expose only a phase-specific message.
            error = switch (phase) {
                case UPLOADING -> "上传测速失败，请检查远端连接、写入权限和可用空间";
                case DOWNLOADING -> "下载测速失败或测试数据校验不一致，请检查远端连接和读取权限";
                default -> "无法准备测速文件，请检查服务器临时目录和可用空间";
            };
        } finally {
            publish(job, Status.CLEANING, uploadRate, downloadRate, uploadMillis, downloadMillis, error, null);
            if (remoteAttempted) {
                try {
                    remote.delete(job.provider, objectKey);
                } catch (Exception ex) {
                    warning = "远端测试文件未能自动清理，请恢复连接后手动删除对象：" + objectKey;
                }
            }
            if (sample != null) {
                try {
                    Files.deleteIfExists(sample);
                } catch (Exception ex) {
                    String localWarning = "服务器测试文件未能自动清理：speed-tests/" + sample.getFileName();
                    warning = warning == null ? localWarning : warning + "；" + localWarning;
                }
            }
            synchronized (this) {
                publish(job, error == null ? Status.COMPLETED : Status.FAILED,
                        uploadRate, downloadRate, uploadMillis, downloadMillis, error, warning);
                running = false;
            }
        }
    }

    private static byte[] prepare(Path sample) throws IOException {
        MessageDigest digest = sha256();
        SecureRandom random = new SecureRandom();
        byte[] buffer = new byte[64 * 1024];
        try (var output = Files.newOutputStream(sample)) {
            for (long remaining = SAMPLE_BYTES; remaining > 0;) {
                random.nextBytes(buffer);
                int length = (int) Math.min(buffer.length, remaining);
                output.write(buffer, 0, length);
                digest.update(buffer, 0, length);
                remaining -= length;
            }
        }
        return digest.digest();
    }

    private static byte[] downloadHash(InputStream input) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        long received = 0;
        while (received < SAMPLE_BYTES) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, SAMPLE_BYTES - received));
            if (count == -1) throw new IOException("Speed test response was truncated");
            if (count == 0) continue;
            received += count;
            digest.update(buffer, 0, count);
        }
        if (input.read() != -1) throw new IOException("Speed test response exceeded sample size");
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void publish(Job job, Status status, Double uploadRate, Double downloadRate,
                                Long uploadMillis, Long downloadMillis, String error, String warning) {
        SpeedTest previous = job.snapshot;
        boolean finished = status == Status.COMPLETED || status == Status.FAILED;
        job.snapshot = new SpeedTest(previous.id(), previous.providerId(), status, SAMPLE_BYTES,
                uploadRate, downloadRate, uploadMillis, downloadMillis, previous.startedAt(),
                finished ? Instant.now() : null, error, warning);
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdown();
        try {
            // Give an in-flight transfer a bounded opportunity to remove its temporary objects.
            worker.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
