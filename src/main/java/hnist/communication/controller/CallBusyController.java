package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.dto.CallBusyMarkRequest;
import hnist.communication.registry.CallBusyRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 通话/会议忙碌状态登记，供会议添加成员时排除忙碌用户。 */
@RestController
@RequestMapping("/api/call")
@RequiredArgsConstructor
public class CallBusyController {

    private final CallBusyRegistry callBusyRegistry;

    /** 标记忙碌；客户端每 30s 心跳续约，服务端 90s 租约到期后自动清除。 */
    @PostMapping("/busy")
    public Result<Void> markBusy(@Valid @RequestBody CallBusyMarkRequest req,
                                 Authentication authentication) {
        callBusyRegistry.mark((Long) authentication.getPrincipal(), req.getBusyType());
        return Result.success();
    }

    /** 释放忙碌（通话/会议结束时调用；异常退出由租约过期兜底） */
    @DeleteMapping("/busy/{userId}")
    public Result<Void> releaseBusy(@PathVariable Long userId, Authentication authentication) {
        // 以 JWT principal 为准释放，路径中的 userId 不参与判定，防止替他人取消忙碌。
        callBusyRegistry.release((Long) authentication.getPrincipal());
        return Result.success();
    }

    @GetMapping("/busy")
    public Result<Set<Long>> getBusyUsers() {
        return Result.success(callBusyRegistry.getBusyUserIds());
    }
}
