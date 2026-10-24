package hnist.communication.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hnist.communication.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 位置 WebSocket：维护群/用户会话表、在线心跳淘汰与群内广播。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final LocationService locationService;

    // groupId -> (userId -> session)
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, WebSocketSession>> groups = new ConcurrentHashMap<>();

    // 在线是有期限的连接存活证明，不是“曾经连过”。使用单调时钟，避免服务器校时误判。
    static final long PRESENCE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(120);
    private final ConcurrentHashMap<WebSocketSession, Long> lastActivityNanos = new ConcurrentHashMap<>();
    // 旧PC群组参数不兼容时，只登记在线，不加入任何群广播房间。
    private final ConcurrentHashMap<Long, WebSocketSession> presenceOnlyUsers = new ConcurrentHashMap<>();
    // 会话 → 最近一次心跳探测的载荷：只接受回显该载荷的 PONG，迟到或伪造的应答不能续期。
    private final ConcurrentHashMap<WebSocketSession, byte[]> pendingPings = new ConcurrentHashMap<>();
    private final AtomicLong pingSequence = new AtomicLong();

    long presenceNowNanos() {
        return System.nanoTime();
    }

    // 已被替换但 onClose 尚未触发的旧会话：TCP 关闭有延迟时 isOpen() 仍为 true，
    // broadcast() 必须先跳过它们，否则同一设备换账号后会继续收到上一个账号的广播。
    private final Set<WebSocketSession> deadSessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long groupId = getAttr(session, "groupId");
        Long userId = getAttr(session, "userId");
        // 在线口径只以本类会话表为准，不再续约 LoginSessionRegistry 的登录占用。
        if (userId != null && (isPresenceOnly(session) || groupId != null)) {
            lastActivityNanos.put(session, presenceNowNanos());
            AtomicReference<WebSocketSession> replaced = new AtomicReference<>();
            // put 返回被替换的旧会话：切群/重连/换账号都会替换，必须主动关闭，否则旧 socket 仍可能收到广播；
            // 注册与空房间清理放在同一个 compute 内，避免旧连接的清理删掉刚注册的新房间。
            if (isPresenceOnly(session)) {
                replaced.set(presenceOnlyUsers.put(userId, session));
            } else {
                groups.compute(groupId, (id, members) -> {
                    if (members == null) members = new ConcurrentHashMap<>();
                    replaced.set(members.put(userId, session));
                    return members;
                });
            }
            WebSocketSession old = replaced.get();
            if (old != null && old != session) {
                // 先标记 dead 再 close：close 完成前 isOpen() 仍为 true，广播必须先跳过该会话。
                retireSession(old, "连接替换");
            }
        }
    }

    private boolean isPresenceOnly(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get("presenceOnly"));
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        if (!isCurrentSession(session)) return;
        byte[] expected = pendingPings.get(session);
        if (expected == null) return;
        ByteBuffer payload = message.getPayload().asReadOnlyBuffer();
        if (payload.remaining() != expected.length) return;
        byte[] received = new byte[payload.remaining()];
        payload.get(received);
        // 只有当前会话对最近一次探测的真实应答才续期，迟到/伪造PONG不能复活旧连接。
        if (Arrays.equals(expected, received) && pendingPings.remove(session, expected)) touchSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 已替换/过期会话的迟到消息不能续约，也不能污染新会话。
        if (!isCurrentSession(session)) return;
        try {
            Map<String, Object> msg = mapper.readValue(message.getPayload(),
                    new TypeReference<Map<String, Object>>() {});
            String type = msg == null ? null : (String) msg.get("type");
            if (type == null) {
                log.warn("WebSocket 消息缺少 type 字段，已忽略: session={}", session.getId());
                return;
            }
            switch (type) {
                case "ping" -> {
                    if (touchSession(session)) sendToSession(session, Map.of("type", "pong"));
                }
                case "location" -> handleLocation(session, msg);
                case "voice_broadcast" -> handleVoice(session, msg);
                default -> log.warn("未知消息类型: type={}, session={}", type, session.getId());
            }
        } catch (Exception e) {
            // 单条坏消息只记日志并丢弃，不关闭会话，避免一个客户端错误导致其整体掉线。
            log.warn("WebSocket 消息处理异常，已忽略: session={}, err={}",
                    session.getId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleLocation(WebSocketSession session, Map<String, Object> msg) {
        if (isPresenceOnly(session)) return;
        Long userId = getAttr(session, "userId");
        Long groupId = getAttr(session, "groupId");
        Object dataObj = msg.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            log.warn("location 消息缺少 data，已忽略: session={}", session.getId());
            return;
        }
        if (!touchSession(session)) return;

        double lat = toDouble(data.get("latitude"));
        double lng = toDouble(data.get("longitude"));
        double speed = toDouble(data.get("speed"));
        double accuracy = toDouble(data.get("accuracy"));
        double direction = toDouble(data.get("direction"));
        // visible 缺省 true：关闭定位的客户端仍上报坐标以维持在线，peer 收到 visible=false 后会移除其地图标记。
        boolean visible = !(data.get("visible") instanceof Boolean b) || b;

        locationService.updateLocation(userId, lat, lng, null, speed, direction, accuracy);

        // username 是 String，必须用 getAttrStr 取；getAttr 只认 Long。
        String username = getAttrStr(session, "username");

        broadcast(groupId, Map.of("type", "location_broadcast", "data", Map.of("members",
                java.util.List.of(Map.of(
                        "userId", userId.toString(),
                        "userName", username != null ? username : "",
                        "groupId", groupId.toString(),
                        "latitude", lat,
                        "longitude", lng,
                        "speed", speed,
                        "direction", direction,
                        "accuracy", accuracy,
                        "visible", visible,
                        "timestamp", System.currentTimeMillis()
                ))
        )));
    }

    @SuppressWarnings("unchecked")
    private void handleVoice(WebSocketSession session, Map<String, Object> msg) {
        if (isPresenceOnly(session)) return;
        if (!touchSession(session)) return;
        Long groupId = getAttr(session, "groupId");
        broadcast(groupId, msg);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 旧 session 终于关闭，可以从 deadSessions 移除标记。
        deadSessions.remove(session);
        lastActivityNanos.remove(session);
        pendingPings.remove(session);
        Long groupId = getAttr(session, "groupId");
        Long userId = getAttr(session, "userId");
        if (removeCurrentSession(session)) {
            log.info("位置 WS 断开: userId={}, groupId={}", userId, groupId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        retireSession(session, "传输错误");
    }

    private boolean isCurrentSession(WebSocketSession session) {
        Long groupId = getAttr(session, "groupId");
        Long userId = getAttr(session, "userId");
        if (isPresenceOnly(session)) {
            return userId != null && presenceOnlyUsers.get(userId) == session && !deadSessions.contains(session);
        }
        ConcurrentHashMap<Long, WebSocketSession> members = groupId == null ? null : groups.get(groupId);
        return userId != null && members != null && members.get(userId) == session
                && !deadSessions.contains(session);
    }

    private boolean touchSession(WebSocketSession session) {
        if (!isCurrentSession(session) || !session.isOpen()) return false;
        // computeIfPresent 与超时检查同一把 key 锁；被淘汰的连接不能靠迟到消息复活。
        return lastActivityNanos.computeIfPresent(session, (s, previous) -> presenceNowNanos()) != null;
    }

    private boolean removeCurrentSession(WebSocketSession session) {
        Long groupId = getAttr(session, "groupId");
        Long userId = getAttr(session, "userId");
        if (isPresenceOnly(session)) return userId != null && presenceOnlyUsers.remove(userId, session);
        if (groupId == null || userId == null) return false;
        AtomicBoolean removed = new AtomicBoolean();
        groups.computeIfPresent(groupId, (id, members) -> {
            removed.set(members.remove(userId, session));
            return members.isEmpty() ? null : members;
        });
        return removed.get();
    }

    private void retireSession(WebSocketSession session, String reason) {
        deadSessions.add(session);
        lastActivityNanos.remove(session);
        pendingPings.remove(session);
        boolean removed = removeCurrentSession(session);
        if (removed) {
            log.info("位置 WS 离线: userId={}, reason={}", getAttr(session, "userId"), reason);
        }
        try {
            synchronized (session) {
                if (session.isOpen()) session.close(CloseStatus.GOING_AWAY);
            }
        } catch (Exception e) {
            log.warn("关闭失效位置 WS 失败: userId={}, reason={}", getAttr(session, "userId"), reason, e);
        } finally {
            if (!session.isOpen()) deadSessions.remove(session);
        }
    }

    private boolean removeIfInactive(WebSocketSession session, long now) {
        if (!isCurrentSession(session)) return true;
        AtomicBoolean expired = new AtomicBoolean();
        // 在 compute 内复核 lastSeen，与 touchSession 的 computeIfPresent 串行，避免刚续期的连接被误淘汰。
        lastActivityNanos.compute(session, (s, lastSeen) -> {
            if (lastSeen != null && s.isOpen() && !deadSessions.contains(s)
                    && now - lastSeen < PRESENCE_TIMEOUT_NANOS) return lastSeen;
            expired.set(true);
            return null;
        });
        if (expired.get()) retireSession(session, "连接关闭或120秒无有效心跳");
        return expired.get();
    }

    // 无人查看人员列表时也会淘汰静默失联；只关闭位置 WS，不操作 IM/媒体/通话窗口。
    @Scheduled(fixedDelay = 5000)
    public void expireInactiveSessions() {
        long now = presenceNowNanos();
        for (WebSocketSession session : lastActivityNanos.keySet()) removeIfInactive(session, now);
    }

    // 浏览器通常由网络栈自动回 PONG、不发 App 的 JSON ping；探测本身不续期，无应答仍按 120 秒淘汰。
    @Scheduled(fixedDelay = 30000)
    public void probeConnectedSessions() {
        for (WebSocketSession session : lastActivityNanos.keySet()) {
            if (removeIfInactive(session, presenceNowNanos())) continue;
            try {
                synchronized (session) {
                    if (!isCurrentSession(session) || !session.isOpen()) continue;
                    byte[] payload = ByteBuffer.allocate(Long.BYTES).putLong(pingSequence.incrementAndGet()).array();
                    pendingPings.put(session, payload);
                    session.sendMessage(new PingMessage(ByteBuffer.wrap(payload)));
                }
            } catch (Exception e) {
                log.warn("位置 WS 心跳探测失败: userId={}", getAttr(session, "userId"));
                retireSession(session, "心跳探测发送失败");
            }
        }
    }

    private void sendSerialized(WebSocketSession session, WebSocketMessage<?> message) throws IOException {
        // 同一 session 的发送（广播、心跳探测、业务回复）都经这把 session 锁串行化，避免并发写交错。
        synchronized (session) {
            if (isCurrentSession(session) && session.isOpen()) session.sendMessage(message);
        }
    }

    private void broadcast(Long groupId, Object msg) {
        ConcurrentHashMap<Long, WebSocketSession> members = groups.get(groupId);
        if (members == null) return;
        String json;
        try {
            json = mapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("WebSocket 广播序列化失败", e);
            return;
        }
        TextMessage tm = new TextMessage(json);
        // 逐个会话发送并单独捕获异常：一个失效会话发送失败不能中断循环，否则同群其他成员都收不到消息。
        for (Map.Entry<Long, WebSocketSession> entry : members.entrySet()) {
            WebSocketSession s = entry.getValue();
            // 跳过已被替换但 onClose 尚未触发的旧会话，避免消息串到上一个登录账号。
            if (deadSessions.contains(s)) continue;
            if (removeIfInactive(s, presenceNowNanos())) continue;
            try {
                sendSerialized(s, tm);
            } catch (Exception e) {
                // 发送失败的会话必须 close 而不只是 remove：TCP 仍活时客户端心跳正常、不会重连，会永远收不到广播。
                log.warn("WebSocket 发送失败，移除并关闭失效会话: userId={}", entry.getKey(), e);
                retireSession(s, "广播发送失败");
            }
        }
    }

    /** 向群内在线成员广播，业务层发送群消息时调用。 */
    public void pushToGroup(Long groupId, Object msg) {
        broadcast(groupId, msg);
    }

    /** 按已认证账号投递变更，单聊双方不必属于同一个群。 */
    public void pushToUser(Long userId, Object msg) {
        for (WebSocketSession session : lastActivityNanos.keySet()) {
            if (userId.equals(getAttr(session, "userId")) && isCurrentSession(session)
                    && !removeIfInactive(session, presenceNowNanos())) sendToSession(session, msg);
        }
    }

    /** 返回群内在线用户ID，并淘汰超时会话；presenceOnly 连接按其 groupId 属性计入。 */
    public Set<Long> getOnlineUsers(Long groupId) {
        ConcurrentHashMap<Long, WebSocketSession> members = groups.get(groupId);
        Set<Long> online = new HashSet<>();
        long now = presenceNowNanos();
        if (members != null) {
            for (Map.Entry<Long, WebSocketSession> entry : members.entrySet()) {
                if (!removeIfInactive(entry.getValue(), now)) online.add(entry.getKey());
            }
        }
        for (Map.Entry<Long, WebSocketSession> entry : presenceOnlyUsers.entrySet()) {
            if (groupId != null && groupId.equals(getAttr(entry.getValue(), "groupId"))
                    && !removeIfInactive(entry.getValue(), now)) online.add(entry.getKey());
        }
        return online;
    }

    /**
     * 返回全局在线用户ID（含 presenceOnly 连接），供手机摄像头与通讯录在线状态共用，保证两处口径一致。
     */
    public Set<Long> getOnlineUserIds() {
        Set<Long> online = new HashSet<>();
        long now = presenceNowNanos();
        for (WebSocketSession session : lastActivityNanos.keySet()) {
            if (!removeIfInactive(session, now)) online.add(getAttr(session, "userId"));
        }
        return online;
    }

    /**
     * 登出时关闭该用户的全部位置 WS 会话，使通讯录立即显示离线；返回实际关闭的会话数。
     * 只关闭服务端会话，不修改登录代际号，重新登录会由 startNewSession 推进代际使旧 token 失效。
     */
    public int closeUserSessions(Long userId) {
        if (userId == null) {
            return 0;
        }
        int closed = 0;
        WebSocketSession presence = presenceOnlyUsers.get(userId);
        if (presence != null) {
            retireSession(presence, "退出登录");
            closed++;
        }
        for (Map.Entry<Long, ConcurrentHashMap<Long, WebSocketSession>> groupEntry : groups.entrySet()) {
            AtomicReference<WebSocketSession> removed = new AtomicReference<>();
            groups.computeIfPresent(groupEntry.getKey(), (id, members) -> {
                removed.set(members.remove(userId));
                return members.isEmpty() ? null : members;
            });
            WebSocketSession session = removed.get();
            if (session == null) {
                continue;
            }
            retireSession(session, "退出登录");
            closed++;
        }
        if (closed > 0) {
            log.info("登出：已关闭用户位置 WS 会话 {} 个, userId={}", closed, userId);
        }
        return closed;
    }

    private void sendToSession(WebSocketSession session, Object msg) {
        try {
            sendSerialized(session, new TextMessage(mapper.writeValueAsString(msg)));
        } catch (Exception e) {
            log.error("WebSocket 发送消息失败", e);
            retireSession(session, "回复发送失败");
        }
    }

    private Long getAttr(WebSocketSession session, String key) {
        Object v = session.getAttributes().get(key);
        return v instanceof Long l ? l : null;
    }

    /** 获取 String 类型会话属性（如 username；getAttr 只认 Long，String 必须走这里） */
    private String getAttrStr(WebSocketSession session, String key) {
        Object v = session.getAttributes().get(key);
        return v instanceof String s ? s : null;
    }

    private double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
