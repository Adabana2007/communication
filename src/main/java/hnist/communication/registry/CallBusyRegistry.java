package hnist.communication.registry;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 通话/会议忙碌状态注册表：内存租约 + 客户端续约，进程异常退出时租约自动过期。 */
@Component
public class CallBusyRegistry {

    /** 忙碌租约时长：客户端心跳 30s 续约一次，留 3 倍余量防网络抖动 */
    private static final long LEASE_TTL_MS = 90_000L;

    /** userId -> 忙碌记录（忙碌类型 + 到期时间戳） */
    private final ConcurrentHashMap<Long, BusyEntry> busyMap = new ConcurrentHashMap<>();

    private record BusyEntry(String busyType, long expireAt) {}

    /** 标记忙碌并续约；busyType 为 call（双人通话）或 conference（多人会议）。 */
    public void mark(Long userId, String busyType) {
        if (userId == null) return;
        purgeExpired();
        busyMap.put(userId, new BusyEntry(
                busyType == null || busyType.isEmpty() ? "call" : busyType,
                System.currentTimeMillis() + LEASE_TTL_MS));
    }

    public void release(Long userId) {
        if (userId != null) busyMap.remove(userId);
    }

    public Set<Long> getBusyUserIds() {
        purgeExpired();
        return new HashSet<>(busyMap.keySet());
    }

    /** 惰性清理过期租约（读/写时顺手执行，无需常驻定时器） */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        busyMap.entrySet().removeIf(e -> e.getValue().expireAt() < now);
    }
}
