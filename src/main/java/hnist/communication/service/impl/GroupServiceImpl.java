package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.dto.GroupCreateRequest;
import hnist.communication.dto.GroupUpdateRequest;
import hnist.communication.entity.Group;
import hnist.communication.entity.GroupMember;
import hnist.communication.entity.User;
import hnist.communication.mapper.GroupMapper;
import hnist.communication.mapper.GroupMemberMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.GroupService;
import hnist.communication.service.UserService;
import hnist.communication.vo.GroupVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 群组的创建、解散、信息维护与成员归属切换。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    private static final int MAX_PAGE_SIZE = 200;

    /** 未分类组ID */
    static final Long UNCATEGORIZED_GROUP_ID = 1L;

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserMapper userMapper;
    private final UserService userService;

    @Override
    @Transactional
    public GroupVO createGroup(GroupCreateRequest req) {
        // 群名不可重复
        LambdaQueryWrapper<Group> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Group::getName, req.getName());
        if (groupMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("群组名称已存在");
        }
        Long ownerId = getUserIdByUsername(req.getOwnerUsername());
        if (ownerId == null) {
            throw new BusinessException("群主用户名不存在");
        }

        // 创建者若仍是其他群的 owner，removeUserFromCurrentGroup 会抛异常阻止建群。
        removeUserFromCurrentGroup(ownerId);

        Group group = Group.builder()
                .name(req.getName()).ownerId(ownerId).avatar(req.getAvatar()).build();
        Group deleted = groupMapper.selectByNameIncludingDeleted(req.getName());
        if (deleted != null && Boolean.TRUE.equals(deleted.getDeleted())) {
            group.setGroupId(deleted.getGroupId());
            groupMapper.restoreDeleted(group);
        } else if (deleted != null) {
            throw new BusinessException("群组名称已存在");
        } else {
            groupMapper.insert(group);
        }

        GroupMember member = GroupMember.builder()
                .groupId(group.getGroupId()).userId(ownerId).role(GroupMember.ROLE_OWNER).build();
        ensureMembership(member);

        moveUserToGroup(ownerId, group.getGroupId());

        return GroupVO.from(group, getUsername(ownerId));
    }

    @Override
    public Page<GroupVO> listGroups(int page, int size, String name) {
        Page<Group> pageParam = new Page<>(Math.max(1, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
        LambdaQueryWrapper<Group> wrapper = new LambdaQueryWrapper<>();
        if (name != null && !name.isEmpty()) {
            wrapper.like(Group::getName, name);
        }
        wrapper.orderByDesc(Group::getCreatedAt);
        Page<Group> result = groupMapper.selectPage(pageParam, wrapper);

        // 批量查群主用户名，避免 N+1
        List<Long> ownerIds = result.getRecords().stream()
                .map(Group::getOwnerId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> usernameMap = ownerIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectByIds(ownerIds).stream()
                .collect(Collectors.toMap(User::getUserId, User::getUsername, (left, right) -> left));

        return (Page<GroupVO>) result.convert(g -> GroupVO.from(g, usernameMap.get(g.getOwnerId())));
    }

    @Override
    @Transactional
    public void deleteGroup(String idOrName, Long operatorId) {
        // 支持按群组ID或群组名称删除
        Group group = findGroup(idOrName);
        if (group == null) {
            throw new BusinessException("群组不存在");
        }

        Long groupId = group.getGroupId();
        if (UNCATEGORIZED_GROUP_ID.equals(groupId)) {
            throw new BusinessException("未分类组不可删除");
        }

        // 仅 owner 可删除（唯一超级管理员不受限制）
        if (!isSuperAdmin(operatorId)) {
            checkIsOwner(groupId, operatorId);
        }

        // 先收集成员再删除成员记录：解散后要补插未分类组成员记录，否则原成员不会出现在任何成员列表。
        List<GroupMember> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId));

        // 一条 UPDATE 批量改 users.default_group_id，避免逐成员读写。
        userService.batchMoveToGroup(groupId, UNCATEGORIZED_GROUP_ID);

        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId));

        // 补插未分类组成员记录，已在未分类组的成员跳过。
        for (GroupMember m : members) {
            if (getMember(UNCATEGORIZED_GROUP_ID, m.getUserId()) == null) {
                ensureMembership(GroupMember.builder()
                        .groupId(UNCATEGORIZED_GROUP_ID).userId(m.getUserId())
                        .role(GroupMember.ROLE_MEMBER).build());
            }
        }

        groupMapper.deleteById(groupId);
    }

    @Override
    public GroupVO getGroupById(Long id) {
        Group group = groupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException("群组不存在");
        }
        return GroupVO.from(group, getUsername(group.getOwnerId()));
    }

    @Override
    @Transactional
    public GroupVO updateGroup(Long id, GroupUpdateRequest req, Long operatorId) {
        Group group = groupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException("群组不存在");
        }
        if (UNCATEGORIZED_GROUP_ID.equals(group.getGroupId())) {
            throw new BusinessException("未分类组不可编辑");
        }
        // 仅 owner 可编辑（唯一超级管理员不受限制）
        if (!isSuperAdmin(operatorId)) {
            checkIsOwner(id, operatorId);
        }
        Long ownerId = null;
        if (req.getOwnerUsername() != null && !req.getOwnerUsername().isEmpty()) {
            ownerId = getUserIdByUsername(req.getOwnerUsername());
            if (ownerId == null) {
                throw new BusinessException("群主用户名不存在");
            }
        }
        if (req.getName() != null && !req.getName().isEmpty()) group.setName(req.getName());
        if (req.getAvatar() != null) group.setAvatar(req.getAvatar());
        // 群主变更必须在同一事务内改 group_info.owner_id 与 group_members.role，避免两处权限判断不一致。
        if (ownerId != null && !ownerId.equals(group.getOwnerId())) {
            Long oldOwnerId = group.getOwnerId();
            group.setOwnerId(ownerId);
            GroupMember oldOwner = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, id)
                    .eq(GroupMember::getUserId, oldOwnerId));
            if (oldOwner != null) {
                oldOwner.setRole(GroupMember.ROLE_MEMBER);
                groupMemberMapper.updateById(oldOwner);
            }
            GroupMember newOwner = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, id)
                    .eq(GroupMember::getUserId, ownerId));
            if (newOwner == null) {
                throw new BusinessException("该用户不在群中，无法成为群主");
            }
            newOwner.setRole(GroupMember.ROLE_OWNER);
            groupMemberMapper.updateById(newOwner);
        }
        groupMapper.updateById(group);
        return GroupVO.from(group, getUsername(group.getOwnerId()));
    }

    // ---- 辅助方法 ----

    /**
     * 将用户划入指定群组（更新 users.default_group_id）
     */
    @Override
    public void moveUserToGroup(Long userId, Long groupId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        user.setGroupId(groupId);
        userMapper.updateById(user);
    }

    /**
     * 根据群组ID或名称查找群组
     * 先按数字ID查，失败再按名称查
     */
    private Group findGroup(String idOrName) {
        try {
            return groupMapper.selectById(Long.valueOf(idOrName));
        } catch (NumberFormatException e) {
            return groupMapper.selectOne(new LambdaQueryWrapper<Group>().eq(Group::getName, idOrName));
        }
    }

    private Long getUserIdByUsername(String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        return user != null ? user.getUserId() : null;
    }

    private String getUsername(Long userId) {
        if (userId == null || userId <= 0) return null;
        User user = userMapper.selectById(userId);
        return user != null ? user.getUsername() : null;
    }

    /** 判断是否为唯一超级管理员 */
    private boolean isSuperAdmin(Long userId) {
        if (userId == null) return false;
        User user = userMapper.selectById(userId);
        return user != null && User.SUPER_ADMIN_USERNAME.equals(user.getUsername())
                && User.isSuperAdmin(user.getRole());
    }

    /** 校验操作者是否为群 owner */
    private void checkIsOwner(Long groupId, Long userId) {
        GroupMember member = getMember(groupId, userId);
        if (member == null || !GroupMember.ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException("仅群主可执行此操作");
        }
    }

    private GroupMember getMember(Long groupId, Long userId) {
        return groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));
    }

    /** 成员关系必须复用逻辑删除行，否则 uk_group_user 会阻止重新加入原群。 */
    private void ensureMembership(GroupMember member) {
        GroupMember existing = groupMemberMapper.selectIncludingDeleted(member.getGroupId(), member.getUserId());
        if (existing == null) {
            groupMemberMapper.insert(member);
            return;
        }
        member.setId(existing.getId());
        if (Boolean.TRUE.equals(existing.getDeleted()) || !java.util.Objects.equals(existing.getRole(), member.getRole())) {
            groupMemberMapper.restoreDeleted(member);
        }
    }

    /**
     * 将用户移出当前群并归入未分类组；用户是当前群 owner 时抛异常拒绝退出。
     * 同时无条件清理未分类组的成员残留，避免旧 group_members 记录让用户出现在错误列表。
     */
    @Override
    public void removeUserFromCurrentGroup(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }

        // default_group_id 为 NULL 也要继续执行未分类残留清理（历史脏数据兜底）
        Long currentGroupId = user.getGroupId();
        if (currentGroupId != null) {
            GroupMember member = getMember(currentGroupId, userId);
            if (member != null) {
                if (GroupMember.ROLE_OWNER.equals(member.getRole())) {
                    throw new BusinessException("你是当前群组的群主，请先转让群主或解散群组");
                }
                groupMemberMapper.deleteById(member.getId());
            }
        }

        // 当前群可能就是未分类组，或历史数据 group_id 为空，因此这里无条件清理一次残留记录。
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, UNCATEGORIZED_GROUP_ID)
                .eq(GroupMember::getUserId, userId));

        user.setGroupId(UNCATEGORIZED_GROUP_ID);
        userMapper.updateById(user);
    }
}
