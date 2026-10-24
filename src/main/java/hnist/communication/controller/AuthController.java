package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.dto.UserRequest;
import hnist.communication.entity.User;
import hnist.communication.service.UserService;
import hnist.communication.vo.UserVO;
import hnist.communication.websocket.LocationWebSocketHandler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 当前用户信息、登出、验证码与自助注册等认证辅助接口。 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    /** 登出时关闭该用户的位置 WS 会话，使通讯录在线状态立即失效。 */
    private final LocationWebSocketHandler locationHandler;

    @Value("${security.registration.enabled:false}")
    private boolean registrationEnabled;

    @GetMapping("/auth/me")
    public Result<Map<String, Object>> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = (Long) auth.getPrincipal();
        UserVO user = userService.getUser(userId);

        List<String> roles;
        List<String> permissions;
        if (User.isSuperAdmin(user.getRole())) {
            roles = List.of("super_admin", "admin");
            permissions = List.of("*:*:*");
        } else {
            roles = List.of("user");
            permissions = List.of();
        }

        return Result.success(Map.of(
            "user", user,
            "roles", roles,
            "permissions", permissions
        ));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            // 登出需立即生效：关闭该用户的位置 WS 会话，使其他客户端的在线状态马上变为离线。
            int closed = locationHandler.closeUserSessions(userId);
            log.info("用户登出: userId={}, 关闭位置WS会话={}", userId, closed);
        }
        return Result.success();
    }

    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        return Result.success(Map.of("uuid", "", "img", ""));
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody UserRequest req) {
        // 注册开关关闭时拒绝自助注册，账号只能由管理员创建。
        if (!registrationEnabled) {
            throw new hnist.communication.exception.BusinessException("当前系统已关闭自主注册，请联系管理员创建账号");
        }
        // 自助注册固定为普通用户角色，忽略请求体中的 role，避免自我提权。
        req.setRole(0);
        userService.createUser(req);
        return Result.success();
    }
}
