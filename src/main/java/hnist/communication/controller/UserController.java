package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.common.Result;
import hnist.communication.dto.UserRequest;
import hnist.communication.dto.UserUpdateRequest;
import hnist.communication.entity.User;
import hnist.communication.service.AccessControlService;
import hnist.communication.service.UserService;
import hnist.communication.util.SensitiveDataMasker;
import hnist.communication.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import hnist.communication.exception.BusinessException;

/** 用户查询、后台维护及个人资料/密码/群组切换。 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AccessControlService accessControl;

    @GetMapping
    public Result<Page<UserVO>> list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "10") int size,
                                     @RequestParam(required = false) String keyword,
                                     Authentication authentication) {
        Page<UserVO> result = userService.listUsers(page, size, keyword);
        result.getRecords().forEach(user -> maskSensitiveFields(user, authentication));
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<UserVO> get(@PathVariable Long id, Authentication authentication) {
        return Result.success(maskSensitiveFields(userService.getUser(id), authentication));
    }

    /** 后台创建用户；禁止通过请求体任命超级管理员。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<UserVO> create(@Valid @RequestBody UserRequest req) {
        if (req.getRole() != null && req.getRole() == User.ROLE_SUPER_ADMIN) {
            throw new BusinessException("不能任命其他超级管理员");
        }
        return Result.success(userService.createUser(req));
    }

    /** 后台编辑用户；超级管理员账号及其角色变更仅固定超级管理员 admin 可操作。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req,
                                 Authentication authentication) {
        UserVO target = userService.getUser(id);
        boolean fixedSuperAdmin = isFixedSuperAdmin(authentication);
        if (User.isSuperAdmin(target.getRole()) && !fixedSuperAdmin) {
            throw new BusinessException("只有超级管理员 admin 可以修改自己的账号");
        }
        if (req.getRole() != null && req.getRole() == User.ROLE_SUPER_ADMIN
                && !User.SUPER_ADMIN_USERNAME.equals(target.getUsername())) {
            throw new BusinessException("不能任命其他超级管理员");
        }
        if (req.getRole() != null && !req.getRole().equals(target.getRole()) && !fixedSuperAdmin) {
            throw new BusinessException("只有超级管理员 admin 可以变更管理员权限");
        }
        return Result.success(userService.updateUser(id, req));
    }

    /** 删除用户；唯一超级管理员 admin 不允许被删除。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id, Authentication authentication) {
        UserVO target = userService.getUser(id);
        if (User.isSuperAdmin(target.getRole()) && !isFixedSuperAdmin(authentication)) {
            throw new BusinessException("唯一超级管理员 admin 不能被其他账号删除");
        }
        userService.deleteUser(id, (Long) authentication.getPrincipal());
        return Result.success();
    }

    @GetMapping("/{id}/profile")
    public Result<UserVO> getProfile(@PathVariable Long id, Authentication authentication) {
        // 通讯录、点对点文字调度和群组聊天需要读取对方昵称/头像；本接口只读且 /api/users/** 已要求登录，
        // 因此不在此做“本人或管理员”校验，避免误拦截正常会话页面。
        return Result.success(maskSensitiveFields(userService.getProfile(id), authentication));
    }

    /** 仅本人或管理员可修改资料。 */
    @PutMapping("/{id}/profile")
    public Result<UserVO> updateProfile(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req,
                                        Authentication authentication) {
        requireSelfOrAdmin(id, authentication);
        // 个人资料接口不得修改角色、登录名和密码：这里强制置空，改密与后台管理走专用接口。
        req.setRole(null);
        req.setUsername(null);
        req.setPassword(null);
        return Result.success(userService.updateProfile(id, req));
    }

    /** 仅本人或管理员可修改密码。 */
    @PutMapping("/{id}/password")
    public Result<Void> updatePassword(@PathVariable Long id, @RequestBody java.util.Map<String, String> body,
                                       Authentication authentication) {
        requireSelfOrAdmin(id, authentication);
        if (body == null || body.get("oldPassword") == null || body.get("oldPassword").isBlank()
                || body.get("newPassword") == null || body.get("newPassword").isBlank()) {
            throw new BusinessException("原密码和新密码不能为空");
        }
        userService.updatePassword(id, body.get("oldPassword"), body.get("newPassword"));
        return Result.success();
    }

    /** 仅本人或管理员可切换群组。 */
    @PutMapping("/{id}/switch-group")
    public Result<Void> switchGroup(@PathVariable Long id, @RequestParam Long groupId,
                                    Authentication authentication) {
        requireSelfOrAdmin(id, authentication);
        userService.switchGroup(id, groupId);
        return Result.success();
    }

    private void requireSelfOrAdmin(Long userId, Authentication authentication) {
        Long currentUserId = (Long) authentication.getPrincipal();
        if (!userId.equals(currentUserId) && !hasAdminRole(authentication)) {
            throw new BusinessException("无权操作其他用户的数据");
        }
    }

    private boolean hasAdminRole(Authentication authentication) {
        return accessControl.hasAdminRole(authentication);
    }

    private UserVO maskSensitiveFields(UserVO user, Authentication authentication) {
        Long currentUserId = accessControl.currentUserId(authentication);
        if (!accessControl.hasAdminRole(authentication) && !currentUserId.equals(user.getUserId())) {
            user.setPhone(SensitiveDataMasker.maskPhone(user.getPhone()));
            user.setEmail(SensitiveDataMasker.maskEmail(user.getEmail()));
        }
        return user;
    }

    private boolean isFixedSuperAdmin(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            return false;
        }
        UserVO operator = userService.getUser(userId);
        return User.SUPER_ADMIN_USERNAME.equals(operator.getUsername())
                && User.isSuperAdmin(operator.getRole());
    }
}
