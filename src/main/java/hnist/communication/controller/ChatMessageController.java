package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.common.Result;
import hnist.communication.entity.ChatMessage;
import hnist.communication.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 点对点聊天消息的查询、发送与离线补收。 */
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    private final hnist.communication.service.ChatQuoteService quotes;

    /** 按稳定ID定位原消息；校验当前账号、会话和本人删除标记。 */
    @GetMapping("/{id}")
    public Result<ChatMessage> locate(@PathVariable Long id, @RequestParam Long peerId, Authentication authentication) {
        return Result.success(quotes.locatePeer((Long) authentication.getPrincipal(), peerId, id));
    }

    @PostMapping
    public Result<ChatMessage> create(@RequestBody ChatMessage message, Authentication authentication) {
        // 发送者以 JWT principal 为准，忽略请求体中的 senderId，防止伪造他人发消息。
        message.setSenderId((Long) authentication.getPrincipal());
        return Result.success(chatMessageService.create(message));
    }

    /** 查询与某人的聊天记录（分页，时间正序）。 */
    @GetMapping
    public Result<Page<ChatMessage>> list(@RequestParam Long peerId,
                                          @RequestParam(required = false) Long userId,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "100") int size,
                                          Authentication authentication) {
        return Result.success(chatMessageService.listMessages(
                (Long) authentication.getPrincipal(), peerId, page, size));
    }

    /** 查询发给本人的消息用于离线补未读；接收者取 JWT 身份，userId 参数被忽略，limit 上限 2000。 */
    @GetMapping("/received")
    public Result<List<ChatMessage>> received(
            @RequestParam Long userId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime since,
            @RequestParam(defaultValue = "500") int limit,
            Authentication authentication) {
        return Result.success(chatMessageService.listReceived(
                (Long) authentication.getPrincipal(), since, limit));
    }
}
