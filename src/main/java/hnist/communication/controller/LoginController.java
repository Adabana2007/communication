package hnist.communication.controller;

import hnist.communication.security.JwtUtils;
import hnist.communication.common.Result;
import hnist.communication.dto.LoginRequest;
import hnist.communication.service.UserService;
import hnist.communication.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 前台与后台的登录入口，签发带登录代际号的 JWT。 */
@RestController
@RequiredArgsConstructor
public class LoginController {

    private final UserService userService;
    private final JwtUtils jwtUtils;

    @PostMapping("/api/login")
    public Result<Map<String, String>> login(@Valid @RequestBody LoginRequest req) {
        UserVO user = userService.login(req);
        return tokenResult(user);
    }

    /** 后台专用登录：普通 App 用户不能通过该入口取得后台会话。 */
    @PostMapping("/api/admin/login")
    public Result<Map<String, String>> loginBackend(@Valid @RequestBody LoginRequest req) {
        UserVO user = userService.loginBackend(req);
        return tokenResult(user);
    }

    private Result<Map<String, String>> tokenResult(UserVO user) {
        // 登录代际号随 JWT 下发（claim gen）：重新登录时代际递增，旧设备的 token 随即失效。
        long generation = user.getGeneration() == null ? 0L : user.getGeneration();
        String token = jwtUtils.generateToken(user.getUserId(), user.getUsername(), user.getRole(), generation);
        return Result.success(Map.of("token", token));
    }
}
