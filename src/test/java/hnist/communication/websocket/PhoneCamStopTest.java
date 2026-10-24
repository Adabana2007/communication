package hnist.communication.websocket;

import hnist.communication.service.PhoneCameraService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.mockito.Mockito.*;

class PhoneCamStopTest {
    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(Map.of("userId", 3L));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test void stopUsesRegisteredDeviceRatherThanUntrustedPayload() throws Exception {
        PhoneCameraService service = mock(PhoneCameraService.class);
        PhoneCamWebSocketHandler handler = new PhoneCamWebSocketHandler(service);
        WebSocketSession session = session("first");
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"register\",\"deviceId\":\"my-phone\",\"deviceName\":\"手机\"}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"stream_stopped\",\"deviceId\":\"other-phone\"}"));
        verify(service).stopStreamByDevice("my-phone");
        verify(service, never()).stopStreamByDevice("other-phone");
    }

    @Test void oldConnectionCannotStopOrOfflineItsReplacement() throws Exception {
        PhoneCameraService service = mock(PhoneCameraService.class);
        PhoneCamWebSocketHandler handler = new PhoneCamWebSocketHandler(service);
        WebSocketSession first = session("first"), second = session("second");
        TextMessage register = new TextMessage("{\"type\":\"register\",\"deviceId\":\"my-phone\",\"deviceName\":\"手机\"}");
        handler.handleTextMessage(first, register);
        handler.handleTextMessage(second, register);
        handler.handleTextMessage(first, new TextMessage("{\"type\":\"stream_stopped\"}"));
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        verify(service, never()).stopStreamByDevice(anyString());
        verify(service, never()).markOffline(anyString());
        handler.afterConnectionClosed(second, CloseStatus.NORMAL);
        verify(service).markOffline("my-phone");
    }
}
