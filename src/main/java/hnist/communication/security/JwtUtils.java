package hnist.communication.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** JWT 签发、解析、校验与临期滑动续期。 */
@Slf4j
@Component
public class JwtUtils {

    private final SecretKey key;
    private final long expiration;

    public JwtUtils(@Value("${jwt.secret}") String secret,
                    @Value("${jwt.expiration}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /**
     * 签发令牌；generation 写入 claim gen（同一账号每次登录 +1），校验方与 LoginSessionRegistry
     * 的当前代际号比对后旧令牌立即失效，缺失时按 0 处理。
     */
    public String generateToken(Long userId, String username, Integer role, long generation) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .claim("gen", generation)
                // JJWT 0.12 的时间参数仍是 java.util.Date；仅在此第三方 API 边界做转换。
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expiration)))
                .signWith(key)
                .compact();
    }

    /** 读取令牌里的登录代际号（claim 缺失 = 0，兼容改造前签发的令牌） */
    public long getGeneration(String token) {
        Object gen = parseToken(token).get("gen");
        if (gen instanceof Number n) {
            return n.longValue();
        }
        if (gen instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.debug("JWT代际号格式非法，按0处理");
                return 0L;
            }
        }
        return 0L;
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // 只记录失败原因，不记录 token 本身，避免凭据进入日志。
            log.warn("JWT令牌校验失败: {}", e.getMessage());
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    /** 续期阈值：剩余有效期低于 2 小时时随请求换新令牌（当前 jwt.expiration 为 24h）。 */
    private static final long REFRESH_THRESHOLD_MS = 2 * 60 * 60 * 1000L;

    /**
     * 剩余有效期低于阈值时按原 claim 重新签发完整有效期的令牌，否则返回 null；
     * 新令牌由 JwtAuthenticationFilter 经 New-Token 响应头下发。
     */
    public String refreshIfExpiring(String token) {
        try {
            Claims claims = parseToken(token);
            var exp = claims.getExpiration();
            if (exp == null) {
                return null;
            }
            Instant expiresAt = exp.toInstant();
            long remaining = expiresAt.toEpochMilli() - System.currentTimeMillis();
            if (remaining > REFRESH_THRESHOLD_MS) {
                return null;
            }
            Long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            Integer role = claims.get("role", Integer.class);
            // 续期必须原样携带登录代际号，否则会把旧设备的令牌洗白成有效代际。
            Object gen = claims.get("gen");
            long generation = (gen instanceof Number n) ? n.longValue() : 0L;
            String renewed = generateToken(userId, username, role, generation);
            log.info("JWT滑动续期: userId={}, 剩余{}分钟", userId, remaining / 60000);
            return renewed;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT滑动续期跳过: {}", e.getMessage());
            return null;
        }
    }
}
