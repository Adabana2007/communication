package hnist.communication.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hnist.communication.service.PhoneCameraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手机摄像头 WebSocket 信令通道
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhoneCamWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final PhoneCameraService phoneCameraService;

    /** deviceId → session */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** sessionId → deviceId */
    private final ConcurrentHashMap<String, String> sessionDevices = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 等 register 消息到达后再放入 sessions
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = mapper.readValue(message.getPayload(),
                new TypeReference<Map<String, Object>>() {});
        String type = (String) msg.get("type");

        switch (type) {
            case "register" -> handleRegister(session, msg);
            case "ping" -> handlePing(session);
            case "stream_ready" -> handleStreamReady(session);
            case "stream_error" -> handleStreamError(session, msg);
            case "stream_rejected" -> handleStreamRejected(session);
            case "stream_stopped" -> {
                String deviceId = sessionDevices.get(session.getId());
                // 只有当前登记的会话才能停止推流，防止旧连接迟到的消息影响新的同名设备连接。
                if (deviceId != null && sessions.get(deviceId) == session) phoneCameraService.stopStreamByDevice(deviceId);
            }
        }
    }

    private void handleRegister(WebSocketSession session, Map<String, Object> msg) {
        String deviceId = (String) msg.get("deviceId");
        String deviceName = (String) msg.get("deviceName");
        Long userId = getAttr(session, "userId");

        if (deviceId == null || deviceId.isEmpty()) {
            sendToSession(session, Map.of("type", "register_error", "message", "deviceId不能为空"));
            return;
        }

        phoneCameraService.registerDevice(deviceId, userId, deviceName);
        // 同一 deviceId 重连时覆盖旧会话；旧会话关闭时 afterConnectionClosed 用条件 remove，不会误删新会话。
        sessions.put(deviceId, session);
        sessionDevices.put(session.getId(), deviceId);
        sendToSession(session, Map.of("type", "register_ok", "deviceId", deviceId));
    }

    private void handlePing(WebSocketSession session) {
        String deviceId = sessionDevices.get(session.getId());
        if (deviceId != null) {
            phoneCameraService.updateHeartbeat(deviceId);
        }
        sendToSession(session, Map.of("type", "pong"));
    }

    private void handleStreamReady(WebSocketSession session) {
        String deviceId = sessionDevices.get(session.getId());
        if (deviceId != null) {
            phoneCameraService.confirmStreamReady(deviceId);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleStreamError(WebSocketSession session, Map<String, Object> msg) {
        String deviceId = sessionDevices.get(session.getId());
        if (deviceId != null) {
            // 把手机上报的失败原因透传给 PhoneCameraService（日志和 /view-status），否则查看方只看到“推流失败”。
            String reason = msg == null ? "" : String.valueOf(msg.getOrDefault("message", ""));
            phoneCameraService.reportStreamError(deviceId, reason);
        }
    }

    /** 设备拒绝查看（授权弹窗点拒绝/权限未授权）时记拒绝标记，供查看方轮询感知。 */
    private void handleStreamRejected(WebSocketSession session) {
        String deviceId = sessionDevices.get(session.getId());
        if (deviceId != null) {
            phoneCameraService.rejectStreamByDevice(deviceId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = sessionDevices.remove(session.getId());
        if (deviceId != null) {
            // 条件删除：旧连接关闭时若同 deviceId 已有新会话，不能把新会话一并移除。
            if (sessions.remove(deviceId, session)) phoneCameraService.markOffline(deviceId);
        }
    }

    /** 设备信令是否在线，请求推流前用它避免把指令发往已断开的连接。 */
    public boolean isDeviceConnected(String deviceId) {
        WebSocketSession session = sessions.get(deviceId);
        return session != null && session.isOpen();
    }

    /** 给指定设备发消息；会话不存在或已关闭时只记 warn，消息不会补发。 */
    public void sendToDevice(String deviceId, Object msg) {
        WebSocketSession session = sessions.get(deviceId);
        if (session != null && session.isOpen()) {
            log.info("发送消息到设备 {}: type={}", deviceId, msg instanceof Map<?,?> m ? m.get("type") : "?");
            sendToSession(session, msg);
        } else {
            log.warn("无法发送消息到设备 {}: session={}, isOpen={}",
                    deviceId, session != null, session != null && session.isOpen());
        }
    }

    private void sendToSession(WebSocketSession session, Object msg) {
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (Exception e) {
            // 发送异常只记日志，不向信令回调抛出，避免单次发送失败中断连接处理。
            log.error("WebSocket 发送消息失败", e);
        }
    }

    private Long getAttr(WebSocketSession session, String key) {
        Object v = session.getAttributes().get(key);
        return v instanceof Long l ? l : null;
    }
}
