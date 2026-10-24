package hnist.communication.security;

import hnist.communication.entity.User;
import hnist.communication.registry.LoginSessionRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 从 Authorization 头解析 JWT，校验登录代际后建立 SecurityContext。 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Bearer Token 前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final LoginSessionRegistry loginSessions;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        // 无 Bearer 头或令牌无效时不在这里拒绝：放行后由 SecurityConfig 的授权规则决定是否返回 401。
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            if (jwtUtils.validateToken(token)) {
                var claims = jwtUtils.parseToken(token);
                Long userId = Long.parseLong(claims.getSubject());
                String username = claims.get("username", String.class);
                Integer role = claims.get("role", Integer.class);

                // 代际号失效说明账号已在其他设备登录：直接写 401 并终止过滤链，不能继续走匿名放行。
                if (!loginSessions.isGenerationValid(userId, jwtUtils.getGeneration(token))) {
                    writeSessionExpired(response);
                    return;
                }

                List<SimpleGrantedAuthority> authorities;
                if (User.isSuperAdmin(role)) {
                    // 超级管理员同时授予 ROLE_ADMIN，才能通过各控制器的 hasRole('ADMIN') 校验。
                    authorities = List.of(
                            new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_ADMIN"));
                } else {
                    authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
                }

                // principal 存 userId、details 存 username，MutationAuditFilter 依赖这个约定取审计主体。
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                auth.setDetails(username);
                SecurityContextHolder.getContext().setAuthentication(auth);
                // 临期令牌经 New-Token 响应头下发；续期保留原代际号，见 JwtUtils.refreshIfExpiring。
                String renewed = jwtUtils.refreshIfExpiring(token);
                if (renewed != null) {
                    response.setHeader("New-Token", renewed);
                }
            }
        }
        chain.doFilter(request, response);
    }

    /** 401 响应体与 SecurityConfig 的 authenticationEntryPoint 保持一致，客户端统一按 401 重新登录。 */
    private void writeSessionExpired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":401,\"message\":\"账号已在其他设备登录，请重新登录\",\"data\":null}");
    }
}
