package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.common.Result;
import hnist.communication.dto.MemberAddRequest;
import hnist.communication.dto.MemberUpdateRequest;
import hnist.communication.service.AccessControlService;
import hnist.communication.service.GroupMemberService;
import hnist.communication.vo.GroupMemberVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 群组成员的批量添加、查询、角色调整与移除。 */
@RestController
@RequestMapping("/api/groups/{groupId}/members")
@RequiredArgsConstructor
public class GroupMemberController {

    private final GroupMemberService groupMemberService;
    private final AccessControlService accessControl;

    @PostMapping
    public Result<Void> addMembers(@PathVariable Long groupId, @Valid @RequestBody MemberAddRequest req,
                                   Authentication authentication) {
        // 操作人取 JWT principal，覆盖请求体中的 operatorId，避免伪造操作者。
        req.setOperatorId((Long) authentication.getPrincipal());
        groupMemberService.addMembers(groupId, req);
        return Result.success();
    }

    @GetMapping
    public Result<Page<GroupMemberVO>> list(@PathVariable Long groupId,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size,
                                            Authentication authentication) {
        accessControl.requireGroupMemberOrAdmin(groupId, authentication, "无权查看该群组成员");
        return Result.success(groupMemberService.listMembers(groupId, page, size));
    }

    @PutMapping("/{userId}")
    public Result<Void> updateMember(@PathVariable Long groupId,
                                      @PathVariable Long userId,
                                      @Valid @RequestBody MemberUpdateRequest req,
                                      Authentication authentication) {
        req.setOperatorId((Long) authentication.getPrincipal());
        groupMemberService.updateMember(groupId, userId, req);
        return Result.success();
    }

    @DeleteMapping
    public Result<Void> removeMembers(@PathVariable Long groupId,
                                       @RequestParam(required = false) Long operatorId,
                                       @RequestParam List<Long> userIds,
                                       Authentication authentication) {
        // 操作者以 JWT 为准，声明的 operatorId 参数不参与判定。
        groupMemberService.removeMembers(groupId, (Long) authentication.getPrincipal(), userIds);
        return Result.success();
    }
}
