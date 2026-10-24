package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.common.Result;
import hnist.communication.dto.GroupCreateRequest;
import hnist.communication.dto.GroupUpdateRequest;
import hnist.communication.service.GroupService;
import hnist.communication.vo.GroupVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

/** 群组创建、查询、编辑与解散。 */
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<GroupVO> create(@Valid @RequestBody GroupCreateRequest req) {
        return Result.success(groupService.createGroup(req));
    }

    @GetMapping
    public Result<Page<GroupVO>> list(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int size,
                                      @RequestParam(required = false) String name) {
        return Result.success(groupService.listGroups(page, size, name));
    }

    @GetMapping("/{id}")
    public Result<GroupVO> get(@PathVariable Long id) {
        return Result.success(groupService.getGroupById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<GroupVO> update(@PathVariable Long id, @Valid @RequestBody GroupUpdateRequest req,
                                  Authentication authentication) {
        return Result.success(groupService.updateGroup(id, req, (Long) authentication.getPrincipal()));
    }

    /** 解散群组：id 可为群组 ID 或名称，仅群主或超级管理员可操作。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable String id, Authentication authentication) {
        groupService.deleteGroup(id, (Long) authentication.getPrincipal());
        return Result.success();
    }

}
