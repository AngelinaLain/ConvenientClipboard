package com.convenientclipboard.config;

import com.convenientclipboard.storage.SettingsService;
import com.convenientclipboard.storage.StorageSettings;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Keeps Tomcat's parser limits tied to a single snapshot taken before each upload. */
@Configuration(proxyBeanMethods = false)
public class DynamicMultipartConfig {
    public static final long MAX_UPLOAD_BYTES = StorageSettings.MAX_UPLOAD_BYTES;
    public static final long REQUEST_OVERHEAD_BYTES = 1024L * 1024;
    private static final ThreadLocal<UploadLimits> CURRENT = new ThreadLocal<>();

    record UploadLimits(long fileBytes, long requestBytes) {}

    static UploadLimits limits(long fileBytes) {
        if (fileBytes < 1 || fileBytes > MAX_UPLOAD_BYTES)
            throw new IllegalStateException("Upload size setting is outside the supported range");
        return new UploadLimits(fileBytes, Math.addExact(fileBytes, REQUEST_OVERHEAD_BYTES));
    }
    static void bind(UploadLimits limits) { CURRENT.set(limits); }
    static void clear() { CURRENT.remove(); }

    @Bean
    public MultipartConfigElement multipartConfigElement(MultipartProperties properties) {
        // No database lookup during servlet startup. Unmatched multipart endpoints retain a small finite limit.
        long bootstrapBytes = Math.max(1L, Math.min(properties.getMaxFileSize().toBytes(), MAX_UPLOAD_BYTES));
        UploadLimits bootstrap = limits(bootstrapBytes);
        return new MultipartConfigElement(properties.getLocation(), bootstrap.fileBytes(), bootstrap.requestBytes(), 0) {
            private UploadLimits current() { return CURRENT.get() == null ? bootstrap : CURRENT.get(); }
            @Override public long getMaxFileSize() { return current().fileBytes(); }
            @Override public long getMaxRequestSize() { return current().requestBytes(); }
        };
    }

    @Bean
    public UploadLimitSnapshotFilter uploadLimitSnapshotFilter(ObjectProvider<SettingsService> settings) {
        return new UploadLimitSnapshotFilter(settings);
    }

    @Bean
    public FilterRegistrationBean<UploadLimitSnapshotFilter> uploadLimitFilterRegistration(UploadLimitSnapshotFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // CSRF can access request parameters, which can trigger multipart parsing before the controller.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("uploadLimitSnapshotFilter");
        return registration;
    }
}
