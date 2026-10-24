package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.dto.MemberAddRequest;
import hnist.communication.dto.MemberUpdateRequest;
import hnist.communication.entity.GroupMember;
import hnist.communication.entity.User;
import hnist.communication.mapper.GroupMemberMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.GroupMemberService;
import hnist.communication.service.GroupService;
import hnist.communication.vo.GroupMemberVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 群成员的加入、角色调整与移除。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMemberServiceImpl implements GroupMemberService {

    private static final int MAX_PAGE_SIZE = 200;

    private final GroupMemberMapper groupMemberMapper;
    private final UserMapper userMapper;
    private final GroupService groupService;

    @Override
    @Transactional
    public void addMembers(Long groupId, MemberAddRequest req) {
        // 加人需要群主或管理员权限；唯一超级管理员 admin 例外，不受群角色限制。
        if (!isSuperAdmin(req.getOperatorId())) {
            checkIsOwnerOrAdmin(groupId, req.getOperatorId());
        }

        for (Long userId : req.getUserIds()) {
            // 已在群中则幂等跳过，重复提交不会插入第二条成员记录。
            if (getMember(groupId, userId) != null) {
                continue;
            }

            // 先把用户从原群移出并归入未分类组；若用户是原群群主，这里会抛异常阻止加入新群。
            groupService.removeUserFromCurrentGroup(userId);

            GroupMember member = GroupMember.builder()
                    .groupId(groupId).userId(userId).role(GroupMember.ROLE_MEMBER).build();
            ensureMembership(member);

            // 当前群归属由 users.default_group_id 决定，插入成员记录后必须同步，否则发消息仍按旧群校验。
            groupService.moveUserToGroup(userId, groupId);
        }
    }

    @Override
    public Page<GroupMemberVO> listMembers(Long groupId, int page, int size) {
        Page<GroupMember> pageParam = new Page<>(Math.max(1, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
        Page<GroupMember> result = groupMemberMapper.selectPage(pageParam,
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .orderByAsc(GroupMember::getJoinedAt));

        // 批量查用户名，避免 N+1。
        List<Long> userIds = result.getRecords().stream()
                .map(GroupMember::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> usernameMap = userIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(User::getUserId, User::getUsername, (left, right) -> left));

        return (Page<GroupMemberVO>) result.convert(m -> {
            GroupMemberVO vo = GroupMemberVO.from(m);
            vo.setUsername(usernameMap.get(m.getUserId()));
            return vo;
        });
    }

    @Override
    @Transactional
    public void updateMember(Long groupId, Long userId, MemberUpdateRequest req) {
        boolean superAdmin = isSuperAdmin(req.getOperatorId());
        // 先校验操作者权限再查询目标成员，避免非管理员探测群成员信息。
        if (!superAdmin) {
            checkIsOwnerOrAdmin(groupId, req.getOperatorId());
        }

        GroupMember member = getMember(groupId, userId);
        if (member == null) {
            throw new BusinessException("该用户不在群中");
        }

        // 不能修改 owner 的角色（群主变更请走编辑群组转让群主）
        if (req.getRole() != null && GroupMember.ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException("不能修改群主的角色");
        }

        // 设置或取消 admin 只有 owner 能做（超级管理员不受限制）
        if (req.getRole() != null) {
            String opRole = getRole(groupId, req.getOperatorId());
            if (!GroupMember.ROLE_OWNER.equals(opRole) && !superAdmin) {
                throw new BusinessException("仅群主可设置管理员");
            }
            member.setRole(req.getRole());
        }
        if (req.getNickname() != null) {
            member.setNickname(req.getNickname());
        }
        groupMemberMapper.updateById(member);
    }

    @Override
    @Transactional
    public void removeMembers(Long groupId, Long operatorId, List<Long> userIds) {
        // 移除成员需要群主或管理员权限；唯一超级管理员 admin 例外。
        if (!isSuperAdmin(operatorId)) {
            checkIsOwnerOrAdmin(groupId, operatorId);
        }

        for (Long userId : userIds) {
            GroupMember member = getMember(groupId, userId);
            if (member == null) {
                continue;
            }
            if (GroupMember.ROLE_OWNER.equals(member.getRole())) {
                throw new BusinessException("不能移除群主");
            }
            groupMemberMapper.deleteById(member.getId());

            // 移除后必须落到未分类组并补成员记录，否则该用户会从所有群的成员列表消失。
            groupService.moveUserToGroup(userId, GroupServiceImpl.UNCATEGORIZED_GROUP_ID);
            if (getMember(GroupServiceImpl.UNCATEGORIZED_GROUP_ID, userId) == null) {
                GroupMember uncategorized = GroupMember.builder()
                        .groupId(GroupServiceImpl.UNCATEGORIZED_GROUP_ID)
                        .userId(userId).role(GroupMember.ROLE_MEMBER).build();
                ensureMembership(uncategorized);
            }
        }
    }

    // ---- 校验方法 ----

    private GroupMember getMember(Long groupId, Long userId) {
        return groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));
    }

    /** 重新加入原群时恢复逻辑删除行，避免唯一键冲突。 */
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

    private String getRole(Long groupId, Long userId) {
        GroupMember member = getMember(groupId, userId);
        return member == null ? null : member.getRole();
    }

    private void checkIsOwnerOrAdmin(Long groupId, Long userId) {
        String role = getRole(groupId, userId);
        if (role == null) {
            throw new BusinessException("你不是群成员，无权操作");
        }
        if (!GroupMember.ROLE_OWNER.equals(role) && !GroupMember.ROLE_ADMIN.equals(role)) {
            throw new BusinessException("仅群主或管理员可执行此操作");
        }
    }

    /** 判断是否为唯一超级管理员 */
    private boolean isSuperAdmin(Long userId) {
        if (userId == null) return false;
        User user = userMapper.selectById(userId);
        return user != null && User.SUPER_ADMIN_USERNAME.equals(user.getUsername())
                && User.isSuperAdmin(user.getRole());
    }
}
