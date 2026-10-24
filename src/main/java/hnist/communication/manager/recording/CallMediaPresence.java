package hnist.communication.manager.recording;

import hnist.communication.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 通话媒体在场性：轮询 MediaMTX 路径 ready 状态并记录双方页面心跳，API 失败时返回未知而不是离线。 */
@Slf4j
@Component
public class CallMediaPresence {
    private final RestTemplate http;
    private final String api;
    private volatile long checkedAt;
    private volatile Map<String, Boolean> paths = Map.of();
    private volatile boolean known;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static class Session { long first, second, touched; final Map<Long, Pulse> pulses = new HashMap<>(); }
    private record Pulse(long at, boolean cameraOff, boolean muted) {}
    public record Health(boolean known, boolean localReady, boolean peerReady, long peerAgeMs,
                         boolean peerCameraOff, boolean peerMuted) {}
    public CallMediaPresence(RestTemplate http, @Value("${mediamtx.api}") String api) { this.http = http; this.api = api; }

    public static void validate(long owner, String room, long peer) {
        if (owner <= 0 || peer <= 0 || owner == peer || room == null || !room.matches("[A-Za-z0-9_-]{5,100}"))
            throw new BusinessException("通话参数无效");
    }

    public Health pulse(long owner, String room, long peer, boolean cameraOff, boolean muted) {
        validate(owner, room, peer); refresh();
        Session existing = sessions.get(room);
        if (existing != null && (existing.first != Math.min(owner, peer) || existing.second != Math.max(owner, peer)))
            throw new BusinessException("通话参与者不匹配");
        if (known && !Boolean.TRUE.equals(paths.get(path(room, owner)))) return health(room, owner, peer);
        if (!sessions.containsKey(room) && sessions.size() >= 4096) throw new BusinessException("通话服务繁忙，请稍后重试");
        // 会话固定按 owner/peer 的 min/max 顺序存储，双方任一方向都能匹配同一会话。
        Session session = sessions.computeIfAbsent(room, key -> { var s = new Session(); s.first = Math.min(owner, peer); s.second = Math.max(owner, peer); return s; });
        synchronized (session) {
            if (session.first != Math.min(owner, peer) || session.second != Math.max(owner, peer)) throw new BusinessException("通话参与者不匹配");
            session.touched = System.currentTimeMillis(); session.pulses.put(owner, new Pulse(session.touched, cameraOff, muted));
        }
        return health(room, owner, peer);
    }

    public void requireStreams(long owner, String room, long peer) {
        validate(owner, room, peer); refresh();
        Session session = sessions.get(room);
        if (session == null || session.first != Math.min(owner, peer) || session.second != Math.max(owner, peer))
            throw new BusinessException("请等待通话媒体建立后再录制");
        // known=false 表示查询失败，此时拒绝录制而不是猜测媒体可用。
        if (!known) throw new BusinessException("暂时无法确认媒体状态，请稍后重试");
        if (!Boolean.TRUE.equals(paths.get(path(room, owner))) || !Boolean.TRUE.equals(paths.get(path(room, peer))))
            throw new BusinessException("双方媒体尚未就绪，无法录制");
    }

    private Health health(String room, long owner, long peer) {
        Session session = sessions.get(room); Pulse pulse = null;
        if (session != null) synchronized (session) { pulse = session.pulses.get(peer); }
        return new Health(known, Boolean.TRUE.equals(paths.get(path(room, owner))), Boolean.TRUE.equals(paths.get(path(room, peer))),
            pulse == null ? -1 : System.currentTimeMillis() - pulse.at, pulse != null && pulse.cameraOff, pulse != null && pulse.muted);
    }

    @SuppressWarnings("unchecked")
    private synchronized void refresh() {
        // 2 秒内复用上次结果；查询失败只置 known=false，由调用方区分“未知”与“路径不存在”。
        long now = System.currentTimeMillis(); if (now - checkedAt < 2000) return; checkedAt = now;
        try {
            Map<String, Object> response = http.getForObject(api + "/v3/paths/list?itemsPerPage=10000", Map.class);
            if (response == null || !(response.get("items") instanceof List<?> items)) throw new IllegalStateException("媒体状态响应无效");
            Map<String, Boolean> next = new HashMap<>();
            for (Object value : items) if (value instanceof Map<?, ?> item) next.put(String.valueOf(item.get("name")), Boolean.TRUE.equals(item.get("ready")));
            paths = next; known = true;
        } catch (Exception e) {
            known = false;
            log.warn("读取通话媒体在场状态失败: {}", e.getMessage());
        }
    }

    // 在场性检查与录制端共用该路径命名，任一处改动都会导致找不到媒体。
    public static String path(String room, long user) { return "stream_" + room + "_" + user; }
    // 超过 30 分钟没有心跳的会话自动移除，防止房间键无限增长。
    @Scheduled(fixedDelay = 60000)
    public void expire() { long cutoff = System.currentTimeMillis() - 30 * 60_000; sessions.entrySet().removeIf(e -> e.getValue().touched < cutoff); }
}
