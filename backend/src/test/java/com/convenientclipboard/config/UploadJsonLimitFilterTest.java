package com.convenientclipboard.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class UploadJsonLimitFilterTest {
    @Test void encodedUploadPathCannotBypassTheManifestLimit() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/%75ploads");
        request.setContent(new byte[UploadJsonLimitFilter.MAX_BYTES+1]);
        var response=new MockHttpServletResponse();
        var called=new AtomicBoolean();
        new UploadJsonLimitFilter().doFilter(request,response,(req,res)->called.set(true));
        assertThat(response.getStatus()).isEqualTo(413);assertThat(called).isFalse();
    }
    @Test void boundsChunkedJsonBeforeParsingAndReplaysValidBody() throws Exception {
        var filter=new UploadJsonLimitFilter();
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/uploads") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent(new byte[UploadJsonLimitFilter.MAX_BYTES+1]);
        var response=new MockHttpServletResponse();
        var called=new AtomicBoolean();
        filter.doFilter(request,response,(req,res)->called.set(true));
        assertThat(response.getStatus()).isEqualTo(413);assertThat(called).isFalse();
        request=new MockHttpServletRequest("POST","/api/uploads");request.setContent("{\"id\":1}".getBytes());
        response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)-> {
            assertThat(req.getInputStream().readAllBytes()).isEqualTo("{\"id\":1}".getBytes());called.set(true);
        });
        assertThat(called).isTrue();
    }
}
