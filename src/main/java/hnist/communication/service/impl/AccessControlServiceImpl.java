package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.GroupMember;
import hnist.communication.mapper.GroupMemberMapper;
import hnist.communication.service.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 统一的资源访问判定，避免各控制器对本人/管理员/同群成员规则理解不一致。 */
@Service
@RequiredArgsConstructor
public class AccessControlServiceImpl implements AccessControlService {

    private final GroupMemberMapper groupMemberMapper;

    @Override
    public boolean hasAdminRole(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    @Override
    public Long currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException("登录状态已过期，请重新登录");
        }
        return userId;
    }

    /** 仅本人或管理员可以访问个人位置与轨迹。 */
    @Override
    public void requireSelfOrAdmin(Long targetUserId, Authentication authentication, String message) {
        Long currentUserId = currentUserId(authentication);
        if (!hasAdminRole(authentication) && !currentUserId.equals(targetUserId)) {
            throw new BusinessException(message);
        }
    }

    /** 仅群成员或管理员可以读取群内位置信息。 */
    @Override
    public void requireGroupMemberOrAdmin(Long groupId, Authentication authentication, String message) {
        Long currentUserId = currentUserId(authentication);
        if (!hasAdminRole(authentication) && !isGroupMember(groupId, currentUserId)) {
            throw new BusinessException(message);
        }
    }

    /** 本人、管理员或同一群组成员可以查看对方共享给群组的信息。 */
    @Override
    public void requireSameGroupOrAdmin(Long targetUserId, Authentication authentication, String message) {
        Long currentUserId = currentUserId(authentication);
        if (hasAdminRole(authentication) || currentUserId.equals(targetUserId)) {
            return;
        }
        if (!isSameGroup(currentUserId, targetUserId)) {
            throw new BusinessException(message);
        }
    }

    @Override
    public boolean isGroupMember(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return false;
        }
        return groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)) > 0;
    }

    @Override
    public boolean isSameGroup(Long firstUserId, Long secondUserId) {
        if (firstUserId == null || secondUserId == null) {
            return false;
        }
        if (firstUserId.equals(secondUserId)) {
            return true;
        }
        List<GroupMember> firstMemberships = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getUserId, firstUserId));
        Set<Long> groupIds = firstMemberships.stream()
                .map(GroupMember::getGroupId)
                .collect(Collectors.toSet());
        if (groupIds.isEmpty()) {
            return false;
        }
        return groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getUserId, secondUserId)
                        .in(GroupMember::getGroupId, groupIds)) > 0;
    }

    /** 返回与指定用户同群的用户 ID 集合（含本人），用于批量过滤资源列表。 */
    @Override
    public Set<Long> sameGroupUserIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        Set<Long> groupIds = groupMemberMapper.selectList(
                        new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId))
                .stream()
                .map(GroupMember::getGroupId)
                .collect(Collectors.toSet());
        if (groupIds.isEmpty()) {
            return Set.of(userId);
        }
        Set<Long> userIds = groupMemberMapper.selectList(
                        new LambdaQueryWrapper<GroupMember>().in(GroupMember::getGroupId, groupIds))
                .stream()
                .map(GroupMember::getUserId)
                .collect(Collectors.toSet());
        userIds.add(userId);
        return userIds;
    }
}
