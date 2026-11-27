package com.convenientclipboard.uploads;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadWebSocketHandlerTest {
    private static final String TOKEN = "a".repeat(64);
    private UploadService uploads;
    private UploadWebSocketHandler handler;
    private WebSocketSession session;
    private UUID id;

    @BeforeEach void setUp() {
        uploads = mock(UploadService.class);
        handler = new UploadWebSocketHandler(uploads, new ObjectMapper());
        session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        id = UUID.randomUUID();
    }

    @Test void authenticatesOnceThenAcceptsIndexedBinaryParts() throws Exception {
        handler.afterConnectionEstablished(session);
        verify(session).setBinaryMessageSizeLimit(UploadTask.CHUNK_SIZE + Integer.BYTES);
        handler.handleTextMessage(session, new TextMessage("{\"id\":\"" + id + "\",\"token\":\"" + TOKEN + "\"}"));
        byte[] body = {1, 2, 3};
        ByteBuffer frame = ByteBuffer.allocate(4 + body.length).putInt(2).put(body);
        frame.flip();
        handler.handleBinaryMessage(session, new BinaryMessage(frame));
        ArgumentCaptor<InputStream> stream = ArgumentCaptor.forClass(InputStream.class);
        verify(uploads).chunk(eq(id), eq(2), eq(TOKEN), isNull(), stream.capture(), eq(3L));
        assertThat(stream.getValue().readAllBytes()).containsExactly(body);
        ArgumentCaptor<TextMessage> replies = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(replies.capture());
        assertThat(replies.getAllValues().get(0).getPayload()).contains("\"ready\"");
        assertThat(replies.getAllValues().get(1).getPayload()).contains("\"ack\"").contains("\"index\":2");
    }

    @Test void rejectsBinaryDataBeforeAuthentication() throws Exception {
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.allocate(4)));
        verifyNoInteractions(uploads);
        ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(reply.capture());
        assertThat(reply.getValue().getPayload()).contains("\"status\":401");
        verify(session).close(any());
    }

    @Test void forwardsAuthenticatedOwnerFromTheUpgradeRequest() throws Exception {
        AppPrincipal owner = new AppPrincipal(UUID.randomUUID(), "member", "USER");
        when(session.getPrincipal()).thenReturn(new UsernamePasswordAuthenticationToken(owner, null));
        handler.handleTextMessage(session, new TextMessage("{\"id\":\"" + id + "\",\"token\":\"" + TOKEN + "\"}"));
        verify(uploads).get(id, TOKEN, owner);
        ByteBuffer frame = ByteBuffer.allocate(5).putInt(0).put((byte) 1);
        frame.flip();
        handler.handleBinaryMessage(session, new BinaryMessage(frame));
        verify(uploads).chunk(eq(id), eq(0), eq(TOKEN), eq(owner), any(), eq(1L));
    }

    @Test void returnsPartValidationErrorWithoutClosingTheConnection() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"id\":\"" + id + "\",\"token\":\"" + TOKEN + "\"}"));
        when(uploads.chunk(eq(id), eq(0), eq(TOKEN), isNull(), any(), eq(1L)))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SHA-256 mismatch"));
        ByteBuffer frame = ByteBuffer.allocate(5).putInt(0).put((byte) 1);
        frame.flip();
        handler.handleBinaryMessage(session, new BinaryMessage(frame));
        ArgumentCaptor<TextMessage> replies = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(replies.capture());
        assertThat(replies.getAllValues().get(1).getPayload()).contains("\"status\":422");
        verify(session, never()).close(any());
    }
}
