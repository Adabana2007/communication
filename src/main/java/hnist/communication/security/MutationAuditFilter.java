package hnist.communication.security;

import hnist.communication.entity.AuditLog;
import hnist.communication.mapper.AuditLogMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

/** 对 /api/ 下会改变数据的方法写审计日志；只记元数据，不记请求体，避免密码和媒体内容落库。 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class MutationAuditFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final AuditLogMapper auditLogMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/") || !MUTATING.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            // 审计放在 finally 中写：业务抛异常时也能留下记录，状态码取响应最终值。
            filterChain.doFilter(request, response);
        } finally {
            try {
                // 沿用 JwtAuthenticationFilter 的约定：principal 为 userId、details 为 username。
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                Long userId = auth != null && auth.getPrincipal() instanceof Long id ? id : null;
                String username = auth != null && auth.getDetails() instanceof String value ? value : null;
                // 经代理时 X-Forwarded-For 首个地址才是客户端 IP，否则取直连地址。
                String forwarded = request.getHeader("X-Forwarded-For");
                String ip = forwarded == null || forwarded.isBlank()
                        ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
                int status = response.getStatus();
                auditLogMapper.insert(AuditLog.builder()
                        .userId(userId).username(username)
                        .method(request.getMethod()).requestPath(request.getRequestURI())
                        .clientIp(ip).responseStatus(status)
                        .success(status < 400 ? 1 : 0).createdAt(LocalDateTime.now()).build());
            } catch (Exception e) {
                // 审计写入失败只记日志，不能因日志表异常改变已经完成的业务响应。
                log.error("写入操作审计日志失败: {} {}", request.getMethod(), request.getRequestURI(), e);
            }
        }
    }

    /** 每天凌晨清理半年以前的审计记录，避免长期运行后日志表无限增长。 */
    @Scheduled(cron = "0 20 3 * * *")
    public void purgeExpiredAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(180);
        int deleted = auditLogMapper.hardDeleteBefore(cutoff);
        if (deleted > 0) {
            log.info("已清理过期操作审计日志: {} 条", deleted);
        }
    }
}
