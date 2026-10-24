package hnist.communication.websocket;

import hnist.communication.security.JwtUtils;
import hnist.communication.entity.User;
import hnist.communication.mapper.UserMapper;
import hnist.communication.registry.LoginSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** 位置 WS 握手鉴权：校验 query 中的 JWT，并把数据库中的群归属写入会话属性。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final JwtUtils jwtUtils;
    private final LoginSessionRegistry loginSessions;
    private final UserMapper userMapper;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query == null) return reject(response, HttpStatus.UNAUTHORIZED, "missing_credentials");

        String token = null;
        String groupId = null;
        for (String param : query.split("&")) {
            int separator = param.indexOf('=');
            if (separator <= 0) continue;
            String name = param.substring(0, separator);
            String value = param.substring(separator + 1);
            if ("token".equals(name)) token = value;
            if ("groupId".equals(name)) groupId = value;
        }

        if (token == null || !jwtUtils.validateToken(token)) {
            return reject(response, HttpStatus.UNAUTHORIZED, "invalid_token");
        }

        var claims = jwtUtils.parseToken(token);
        Long userId;
        Long requestedGroupId = null;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            return reject(response, HttpStatus.UNAUTHORIZED, "invalid_user_id");
        }
        if (groupId != null) {
            try {
                requestedGroupId = Long.parseLong(groupId);
            } catch (NumberFormatException e) {
                // 老PC可能传空值/undefined；解析失败只允许登记在线，不据此加入广播房间。
                log.debug("位置 WS 请求群组号格式非法，按仅在线模式处理: userId={}", userId);
            }
        }
        // 登录代际失效时拒绝握手，否则旧设备会靠重连复活，在线状态与广播都会串到旧设备。
        if (!loginSessions.isGenerationValid(userId, jwtUtils.getGeneration(token))) {
            return reject(response, HttpStatus.UNAUTHORIZED, "superseded_login");
        }
        // 账号在线与群广播授权分离：群组不匹配时仍登记在线，但不能把客户端指定的 groupId 当作广播授权。
        User user = userMapper.selectById(userId);
        if (user == null) {
            return reject(response, HttpStatus.UNAUTHORIZED, "user_not_found");
        }
        boolean presenceOnly = user.getGroupId() == null || !user.getGroupId().equals(requestedGroupId);
        attributes.put("userId", userId);
        // 在线归属只使用数据库记录，绝不使用未授权的请求群组。
        if (user.getGroupId() != null) attributes.put("groupId", user.getGroupId());
        attributes.put("presenceOnly", presenceOnly);
        attributes.put("username", claims.get("username", String.class));
        if (presenceOnly) {
            log.info("位置 WS 仅在线模式: userId={}, requestedGroupId={}, currentGroupId={}",
                    userId, requestedGroupId, user.getGroupId());
        }
        return true;
    }

    private boolean reject(ServerHttpResponse response, HttpStatus status, String reason) {
        response.setStatusCode(status);
        // 不记录URI/query/token，避免凭据进入日志；重复重连仅在DEBUG级别记录。
        log.debug("位置 WS 握手拒绝: reason={}", reason);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {}
}
