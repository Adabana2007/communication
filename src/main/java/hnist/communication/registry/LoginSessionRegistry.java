package hnist.communication.registry;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 登录代际登记表（内存版）：每次登录递增 generation，使旧设备 token 失效。 */
@Component
public class LoginSessionRegistry {

    /** userId -> 当前登录代际号；0 表示该账号在本进程内尚未登录过 */
    private final ConcurrentHashMap<Long, AtomicLong> generations = new ConcurrentHashMap<>();

    /** 代际号 +1 并返回，旧设备此前签发的 token 立即失效；userId 为空返回 0（不校验代际）。 */
    public long startNewSession(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return generations.computeIfAbsent(userId, k -> new AtomicLong())
                .incrementAndGet();
    }

    /** 当前代际号（未登录过返回 0） */
    public long currentGeneration(Long userId) {
        if (userId == null) {
            return 0L;
        }
        AtomicLong gen = generations.get(userId);
        return gen == null ? 0L : gen.get();
    }

    /**
     * 服务端重启后 generations 为空（current=0），此时放行任何代际号，避免把所有在线用户踢下线；
     * 账号重新登录后从 1 递增，旧 token 照旧失效。
     */
    public boolean isGenerationValid(Long userId, long tokenGeneration) {
        long current = currentGeneration(userId);
        if (current == 0L) {
            return true;
        }
        return tokenGeneration >= current;
    }
}
