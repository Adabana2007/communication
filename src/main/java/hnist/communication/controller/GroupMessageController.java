package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.common.Result;
import hnist.communication.dto.GroupMessageRequest;
import hnist.communication.service.GroupMessageService;
import hnist.communication.vo.GroupMessageVO;
import hnist.communication.vo.GroupUnreadVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 群消息发送、查询、离线未读与后台管理。 */
@RestController
@RequestMapping("/api/group-messages")
@RequiredArgsConstructor
public class GroupMessageController {

    private final GroupMessageService groupMessageService;

    private final hnist.communication.service.ChatQuoteService quotes;

    /** 按稳定ID定位原消息；校验当前账号、会话和本人删除标记。 */
    @GetMapping("/{id}")
    public Result<GroupMessageVO> locate(@PathVariable Long id, @RequestParam Long groupId, Authentication authentication) {
        return Result.success(GroupMessageVO.from(quotes.locateGroup((Long) authentication.getPrincipal(), groupId, id)));
    }

    /** 发送群消息；发送者取 JWT 身份，服务端校验其群成员资格。 */
    @PostMapping
    public Result<GroupMessageVO> send(@Valid @RequestBody GroupMessageRequest req,
                                       Authentication authentication) {
        req.setSenderId((Long) authentication.getPrincipal());
        return Result.success(groupMessageService.send(req));
    }

    /** 分页查询指定群的聊天记录（时间倒序） */
    @GetMapping
    public Result<Page<GroupMessageVO>> list(@RequestParam Long groupId,
                                             @RequestParam(required = false) Long requesterId,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             Authentication authentication) {
        return Result.success(groupMessageService.listByGroup(
                groupId, (Long) authentication.getPrincipal(), page, size));
    }

    /**
     * 列出本人所在群及未读数，用于离线补红点；since 只统计该时间之后的他人消息。
     * 请求参数 userId 仅为兼容旧客户端，实际以 JWT 身份为准。
     */
    @GetMapping("/unread")
    public Result<List<GroupUnreadVO>> unread(
            @RequestParam Long userId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime since,
            Authentication authentication) {
        return Result.success(groupMessageService.listUnreadByUser(
                (Long) authentication.getPrincipal(), since));
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Page<GroupMessageVO>> listAll(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size,
                                                @RequestParam(required = false) Long groupId,
                                                @RequestParam(required = false) String keyword) {
        return Result.success(groupMessageService.listAll(page, size, groupId, keyword));
    }

    /** 删除聊天记录；仅超级管理员可操作。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestParam(required = false) Long operatorId,
                               Authentication authentication) {
        groupMessageService.delete(id, (Long) authentication.getPrincipal());
        return Result.success();
    }
}
