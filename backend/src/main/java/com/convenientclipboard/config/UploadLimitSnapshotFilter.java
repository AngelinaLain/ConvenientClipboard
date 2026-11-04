package com.convenientclipboard.config;

import com.convenientclipboard.storage.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

public class UploadLimitSnapshotFilter extends OncePerRequestFilter {
    private final ObjectProvider<SettingsService> settings;

    public UploadLimitSnapshotFilter(ObjectProvider<SettingsService> settings) { this.settings = settings; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/api/files").equals(request.getRequestURI())
                || contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        DynamicMultipartConfig.UploadLimits snapshot;
        try {
            snapshot = DynamicMultipartConfig.limits(settings.getObject().get().maxUploadBytes());
        } catch (RuntimeException ex) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"无法读取上传大小限制，请稍后重试\"}");
            return;
        }
        if (request.getContentLengthLong() > snapshot.requestBytes()) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"文件超过上传大小限制\"}");
            return;
        }
        DynamicMultipartConfig.bind(snapshot);
        try { chain.doFilter(request, response); }
        finally { DynamicMultipartConfig.clear(); }
    }
}
