package com.convenientclipboard.config;

import com.convenientclipboard.common.ApiExceptionHandler;
import com.convenientclipboard.storage.SettingsService;
import com.convenientclipboard.storage.StorageSettings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP and embedded Tomcat, not MockMultipartFile (which bypasses container parsing). */
@SpringBootTest(classes = DynamicMultipartHttpTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.tomcat.max-swallow-size=8MB", "logging.level.org.springframework=ERROR"})
class DynamicMultipartHttpTest {
    private static final long MIB = 1024L * 1024;
    private static final String BOUNDARY = "dynamic-upload-test-boundary";
    private static final Path UPLOAD_TEMP = tempDirectory();
    @LocalServerPort int port;
    @MockitoBean SettingsService settings;
    private final AtomicLong limit = new AtomicLong(MIB);
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            FlywayAutoConfiguration.class, RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
            ManagementWebSecurityAutoConfiguration.class})
    @Import({DynamicMultipartConfig.class, ProbeController.class, ApiExceptionHandler.class})
    static class TestApplication {}

    @RestController
    static class ProbeController {
        @PostMapping("/api/files") Map<String, Long> upload(@RequestParam MultipartFile file) {
            return Map.of("size", file.getSize());
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.location", UPLOAD_TEMP::toString);
    }
    private static Path tempDirectory() {
        try { return Files.createTempDirectory("clipboard-multipart-http-"); }
        catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @BeforeEach void setUp() {
        limit.set(MIB);
        when(settings.get()).thenAnswer(invocation -> new StorageSettings(10L * 1024 * MIB, 50, limit.get()));
    }
    @AfterAll static void deleteTestDirectory() throws IOException {
        try (var paths = Files.walk(UPLOAD_TEMP)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private byte[] body(int size) {
        byte[] prefix = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[prefix.length + size + suffix.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        Arrays.fill(bytes, prefix.length, prefix.length + size, (byte) 'x');
        System.arraycopy(suffix, 0, bytes, prefix.length + size, suffix.length);
        return bytes;
    }
    private HttpRequest request(HttpRequest.BodyPublisher body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY).POST(body).build();
    }
    private HttpResponse<String> upload(int size, boolean chunked) throws Exception {
        byte[] bytes = body(size);
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : HttpRequest.BodyPublishers.ofByteArray(bytes);
        return client.send(request(publisher), HttpResponse.BodyHandlers.ofString());
    }
    private void assertNoTempFiles() throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            try (var files = Files.list(UPLOAD_TEMP)) { if (files.findAny().isEmpty()) return; }
            Thread.sleep(50);
        }
        try (var files = Files.list(UPLOAD_TEMP)) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void increasesAndDecreasesApplyWithoutRestart() throws Exception {
        assertThat(upload((int) MIB + 1, false).statusCode()).isEqualTo(413);
        limit.set(2 * MIB);
        assertThat(upload((int) MIB + 1, false).statusCode()).isEqualTo(200);
        limit.set(MIB);
        assertThat(upload((int) MIB + 1, false).statusCode()).isEqualTo(413);
        verify(settings, times(3)).get();
        assertNoTempFiles();
    }
    @Test void exactFileBoundaryIsAcceptedAndOneExtraByteIsRejected() throws Exception {
        assertThat(upload((int) MIB, false).statusCode()).isEqualTo(200);
        assertThat(upload((int) MIB + 1, false).statusCode()).isEqualTo(413);
        assertNoTempFiles();
    }
    @Test void chunkedBodyIsActuallyCountedByTomcat() throws Exception {
        assertThat(upload((int) MIB, true).statusCode()).isEqualTo(200);
        assertThat(upload((int) MIB + 1, true).statusCode()).isEqualTo(413);
        assertNoTempFiles();
    }
    @Test void totalRequestLimitRejectsKnownAndChunkedOversizedBodies() throws Exception {
        assertThat(upload((int) (3 * MIB), false).statusCode()).isEqualTo(413);
        assertThat(upload((int) (3 * MIB), true).statusCode()).isEqualTo(413);
        assertNoTempFiles();
    }
    @Test void databaseFailureIsClosedBeforeParsing() throws Exception {
        when(settings.get()).thenThrow(new IllegalStateException("test database unavailable"));
        assertThat(upload((int) MIB, false).statusCode()).isEqualTo(503);
        assertNoTempFiles();
    }
    @Test void inFlightRequestKeepsItsParserSnapshot() throws Exception {
        limit.set(2 * MIB);
        var snapshotRead = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(settings.get()).thenAnswer(invocation -> {
            long captured = limit.get();
            snapshotRead.countDown();
            return new StorageSettings(10L * 1024 * MIB, 50, captured);
        });
        var input = new GatedInputStream(body((int) (MIB + MIB / 2)), release);
        var future = client.sendAsync(request(HttpRequest.BodyPublishers.ofInputStream(() -> input)), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            limit.set(MIB);
        } finally { release.countDown(); }
        assertThat(future.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        verify(settings, times(1)).get();
        assertNoTempFiles();
    }
    @Test void beanConstructionAndBootstrapGettersDoNotQueryDatabase() {
        var provider = mock(ObjectProvider.class);
        var properties = new MultipartProperties();
        properties.setLocation(UPLOAD_TEMP.toString());
        var config = new DynamicMultipartConfig();
        var bean = config.multipartConfigElement(properties);
        config.uploadLimitSnapshotFilter(provider);
        assertThat(bean.getMaxFileSize()).isPositive();
        assertThat(bean.getMaxRequestSize()).isGreaterThan(bean.getMaxFileSize());
        assertThat(bean.getFileSizeThreshold()).isZero();
        assertThat(bean.getLocation()).isEqualTo(UPLOAD_TEMP.toString());
        verifyNoInteractions(provider);
    }
    private static class GatedInputStream extends ByteArrayInputStream {
        private final CountDownLatch release;
        GatedInputStream(byte[] bytes, CountDownLatch release) { super(bytes); this.release = release; }
        @Override public synchronized int read(byte[] bytes, int offset, int length) {
            if (pos >= 65536) {
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out awaiting body release"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
            return super.read(bytes, offset, Math.min(length, 65536));
        }
    }
}
