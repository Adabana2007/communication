package hnist.communication.config;

import hnist.communication.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

/** Spring Security 过滤器链：JWT 无状态认证、公开路径白名单与统一的 401 响应。 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigSource()))
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.sameOrigin())
                .referrerPolicy(referrer -> referrer.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(permissions -> permissions.policy(
                    "camera=(), microphone=(), geolocation=()")))
            // 凭据在 Authorization 头而非 Cookie，不存在浏览器自动携带的会话，因此关闭 CSRF 并禁用 Session。
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/api/login",
                    "/api/admin/login",
                    // 接口保持公开以兼容旧客户端，但 AuthController 默认关闭注册并返回业务错误。
                    "/api/register",
                    "/api/captcha",
                    // WS 握手由 WebSocketAuthInterceptor / PhoneCamAuthInterceptor 校验 token，此处只放行 HTTP 层。
                    "/ws/**",
                    "/static/**",
                    "/*.js",
                    "/*.css",
                    "/*.ico",
                    // 离线地图：瓦片/地图包/元数据对内网公开（WebView 内直接加载）
                    "/api/map/tiles/**",
                    "/api/map/regions/active",
                    "/api/map/regions/*/meta",
                    "/api/map/regions/*/package.zip",
                    // 报警媒体（<img>/<audio> 无法带 Authorization header，内网公开）
                    "/api/alarms/media/**"
                ).permitAll()
                // 聊天媒体：仅 GET 公开（<img>/<video>/Image/Video 无 header 直载）；上传仍需 JWT
                .requestMatchers(HttpMethod.GET, "/api/chat/media/**").permitAll()
                .anyRequest().authenticated()
            )
            // 认证失败统一返回 HTTP 401 + JSON：默认 403 空体会让客户端取不到 code，把过期当成功。
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"登录状态已过期，请重新登录\"}");
            }))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigSource() {
        CorsConfiguration config = new CorsConfiguration();
        // API 使用 Authorization 头而非 Cookie；通配来源必须禁止携带凭据，避免反射型 CORS 漏洞。
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // New-Token 必须显式暴露，浏览器端拦截器才能读到滑动续期下发的新令牌。
        config.setExposedHeaders(List.of("Authorization", "New-Token"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
