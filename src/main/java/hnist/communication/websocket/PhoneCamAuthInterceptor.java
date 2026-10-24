package hnist.communication.websocket;

import hnist.communication.security.JwtUtils;
import hnist.communication.registry.LoginSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** 手机摄像头 WS 握手鉴权：从 query 的 token 校验 JWT 与登录代际。 */
@Component
@RequiredArgsConstructor
public class PhoneCamAuthInterceptor implements HandshakeInterceptor {

    private final JwtUtils jwtUtils;
    private final LoginSessionRegistry loginSessions;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query == null) return false;

        String token = null;
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                token = param.substring(6);
                break;
            }
        }

        if (token == null || !jwtUtils.validateToken(token)) return false;

        var claims = jwtUtils.parseToken(token);
        Long userId = Long.parseLong(claims.getSubject());
        // 登录代际失效（账号已在别处登录）时拒绝握手，旧 token 不能重建手机摄像头信令通道。
        if (!loginSessions.isGenerationValid(userId, jwtUtils.getGeneration(token))) {
            return false;
        }
        attributes.put("userId", userId);
        attributes.put("username", claims.get("username", String.class));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {}
}
