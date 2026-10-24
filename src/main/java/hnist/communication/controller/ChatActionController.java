package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.entity.ChatMessageAction;
import hnist.communication.service.ChatActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 聊天消息操作记录的解析、增量同步与提交。 */
@RestController
@RequestMapping("/api/chat/actions")
@RequiredArgsConstructor
public class ChatActionController {
    private final ChatActionService service;

    @GetMapping("/resolve/{type}")
    public Result<Long> resolve(@PathVariable String type, @RequestParam Long conversation,
                                @RequestParam(required = false) String fp,
                                @RequestParam(required = false) Long sender,
                                @RequestParam(required = false) Long timestamp,
                                @RequestParam(required = false) String content, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        // 新客户端带消息指纹 fp 时按 fp 定位；旧客户端无 fp，回退用 sender/timestamp/content 定位。
        return Result.success(fp != null && !fp.isBlank() ? service.resolve(userId, type, conversation, fp)
            : service.resolveLegacy(userId, type, conversation, sender, timestamp, content));
    }

    @GetMapping
    public Result<List<ChatMessageAction>> sync(@RequestParam(defaultValue = "0") long after,
                                               Authentication auth) {
        return Result.success(service.sync((Long) auth.getPrincipal(), after));
    }

    @PostMapping("/{type}/{id}/{action}")
    public Result<ChatMessageAction> operate(@PathVariable String type, @PathVariable Long id,
                                             @PathVariable String action, Authentication auth) {
        return Result.success(service.operate((Long) auth.getPrincipal(), type, id, action));
    }
}
