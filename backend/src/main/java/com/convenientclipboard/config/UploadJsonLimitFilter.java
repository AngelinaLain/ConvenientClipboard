package com.convenientclipboard.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** Bound public JSON bodies before Jackson allocates content, including requests without Content-Length. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+11)
public class UploadJsonLimitFilter extends OncePerRequestFilter {
    static final int MAX_BYTES=2*1024*1024;
    static final int CLIPBOARD_MAX_BYTES=1024*1024;
    private final UrlPathHelper paths=new UrlPathHelper();
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=paths.getPathWithinApplication(request);
        return !"POST".equals(request.getMethod()) || !("/api/uploads".equals(path) || "/api/clipboards".equals(path));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        boolean clipboard="/api/clipboards".equals(paths.getPathWithinApplication(request));
        int limit=clipboard ? CLIPBOARD_MAX_BYTES : MAX_BYTES;
        if(request.getContentLengthLong()>limit) { reject(response,clipboard);return; }
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if(body.length>limit) { reject(response,clipboard);return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private final ByteArrayInputStream input=new ByteArrayInputStream(body);
            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] bytes,int offset,int length) { return input.read(bytes,offset,length); }
                    @Override public boolean isFinished() { return input.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
        },response);
    }
    private void reject(HttpServletResponse response,boolean clipboard) throws IOException {
        response.setStatus(413);response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(clipboard ? "{\"message\":\"粘贴板请求超过 1 MiB 限制\"}" : "{\"message\":\"上传文件清单超过 2 MiB 限制\"}");
    }
}
