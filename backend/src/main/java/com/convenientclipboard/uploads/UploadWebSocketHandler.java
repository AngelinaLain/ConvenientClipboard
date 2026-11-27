package com.convenientclipboard.uploads;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/** One authenticated connection accepts sequential binary parts; clients open several connections for parallel parts. */
@Component
public class UploadWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(UploadWebSocketHandler.class);
    private static final String AUTH = "uploadAuth";
    private static final int AUTH_FRAME_SIZE_LIMIT = 512;
    private static final int MAX_FRAME_BYTES = UploadTask.CHUNK_SIZE + Integer.BYTES;
    private final UploadService uploads;
    private final ObjectMapper json;

    public UploadWebSocketHandler(UploadService uploads, ObjectMapper json) {
        this.uploads = uploads;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(AUTH_FRAME_SIZE_LIMIT);
        session.setBinaryMessageSizeLimit(MAX_FRAME_BYTES);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if (session.getAttributes().containsKey(AUTH)) {
            fail(session, HttpStatus.BAD_REQUEST, "连接已认证", true);
            return;
        }
        try {
            Auth request = json.readValue(message.getPayload(), Auth.class);
            if (request.id() == null) throw new IllegalArgumentException("Missing task id");
            // The token is sent in the first frame, never in a URL or proxy log.
            uploads.get(request.id(), request.token(), principal(session));
            session.getAttributes().put(AUTH, request);
            send(session, Map.of("type", "ready"));
        } catch (ApiException ex) {
            fail(session, ex.status(), ex.getMessage(), true);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            fail(session, HttpStatus.BAD_REQUEST, "上传连接参数无效", true);
        } catch (RuntimeException ex) {
            log.error("WebSocket upload authentication failed", ex);
            fail(session, HttpStatus.INTERNAL_SERVER_ERROR, "上传连接暂时不可用，请重试", true);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        Auth auth = (Auth) session.getAttributes().get(AUTH);
        if (auth == null) {
            fail(session, HttpStatus.UNAUTHORIZED, "请先认证上传任务", true);
            return;
        }
        ByteBuffer payload = message.getPayload();
        if (payload.remaining() < Integer.BYTES || payload.remaining() > MAX_FRAME_BYTES) {
            fail(session, HttpStatus.BAD_REQUEST, "分片数据格式无效", true);
            return;
        }
        int index = payload.getInt();
        try {
            uploads.chunk(auth.id(), index, auth.token(), principal(session), new BufferInput(payload), payload.remaining());
            send(session, Map.of("type", "ack", "index", index));
        } catch (ApiException ex) {
            fail(session, ex.status(), ex.getMessage(), false);
        } catch (RuntimeException ex) {
            log.error("WebSocket upload part failed", ex);
            fail(session, HttpStatus.INTERNAL_SERVER_ERROR, "分片写入失败，请重试", false);
        }
    }

    private AppPrincipal principal(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        return principal instanceof Authentication authentication && authentication.getPrincipal() instanceof AppPrincipal app
                ? app : null;
    }

    private void send(WebSocketSession session, Map<String, ?> data) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(data)));
    }

    private void fail(WebSocketSession session, HttpStatus status, String message, boolean close) throws IOException {
        send(session, Map.of("type", "error", "status", status.value(), "message", message));
        if (close) session.close(CloseStatus.POLICY_VIOLATION);
    }

    private record Auth(UUID id, String token) {}

    private static final class BufferInput extends InputStream {
        private final ByteBuffer buffer;
        private BufferInput(ByteBuffer buffer) { this.buffer = buffer; }
        @Override public int read() { return buffer.hasRemaining() ? buffer.get() & 0xff : -1; }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (length == 0) return 0;
            if (!buffer.hasRemaining()) return -1;
            int count = Math.min(buffer.remaining(), length);
            buffer.get(bytes, offset, count);
            return count;
        }
    }
}
