package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.dto.LoginRequest;
import hnist.communication.dto.UserRequest;
import hnist.communication.dto.UserUpdateRequest;
import hnist.communication.entity.Contact;
import hnist.communication.entity.FriendGroup;
import hnist.communication.entity.Group;
import hnist.communication.entity.GroupMember;
import hnist.communication.entity.LocationHistory;
import hnist.communication.entity.PhoneCamera;
import hnist.communication.entity.TrajectorySession;
import hnist.communication.entity.User;
import hnist.communication.entity.UserLocation;
import hnist.communication.mapper.ContactMapper;
import hnist.communication.mapper.FriendGroupMapper;
import hnist.communication.mapper.GroupMapper;
import hnist.communication.mapper.GroupMemberMapper;
import hnist.communication.mapper.LocationHistoryMapper;
import hnist.communication.mapper.PhoneCameraMapper;
import hnist.communication.mapper.TrajectorySessionMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.mapper.UserLocationMapper;
import hnist.communication.registry.LoginSessionRegistry;
import hnist.communication.service.UserService;
import hnist.communication.vo.UserVO;
import hnist.communication.websocket.LocationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 用户账号管理、登录会话与群归属切换。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final int MAX_PAGE_SIZE = 200;

    private final UserMapper userMapper;
    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final ContactMapper contactMapper;
    private final FriendGroupMapper friendGroupMapper;
    private final UserLocationMapper userLocationMapper;
    private final LocationHistoryMapper locationHistoryMapper;
    private final TrajectorySessionMapper trajectorySessionMapper;
    private final PhoneCameraMapper phoneCameraMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginSessionRegistry loginSessions;
    private final LocationWebSocketHandler locationWebSocketHandler;

    @Override
    public Page<UserVO> listUsers(int page, int size, String keyword) {
        Page<User> pageParam = new Page<>(Math.max(1, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getNickname, keyword));
        }
        wrapper.orderByAsc(User::getUserId);
        Page<User> result = userMapper.selectPage(pageParam, wrapper);
        // 批量查所属群组名，避免逐行查询造成 N+1。
        List<Long> groupIds = result.getRecords().stream()
                .map(User::getGroupId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> groupNameMap = groupIds.isEmpty() ? Collections.emptyMap()
                : groupMapper.selectByIds(groupIds).stream()
                        .collect(Collectors.toMap(Group::getGroupId, Group::getName, (left, right) -> left));
        return (Page<UserVO>) result.convert(u -> {
            UserVO vo = UserVO.from(u);
            vo.setGroupName(groupNameMap.get(u.getGroupId()));
            return vo;
        });
    }

    @Override
    public UserVO getUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return UserVO.from(user);
    }

    @Override
    @Transactional
    public UserVO createUser(UserRequest req) {
        validateRole(req.getUsername(), req.getRole());
        if (userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) > 0) {
            throw new BusinessException("用户名已存在");
        }
        User user = User.builder()
                .username(req.getUsername())
                .password(passwordEncoder.encode(req.getPassword()))
                .nickname(req.getNickname())
                .phone(req.getPhone())
                .role(req.getRole() != null ? req.getRole() : User.ROLE_USER)
                // 新用户默认归属未分类组（default_group_id=1）。
                .groupId(1L)
                .build();
        // 逻辑删除后允许重新创建同名账号：恢复原行，避免 uk_username 冲突，也保留历史关联 ID。
        User deleted = userMapper.selectByUsernameIncludingDeleted(req.getUsername());
        if (deleted != null) {
            if (!Boolean.TRUE.equals(deleted.getDeleted())) {
                throw new BusinessException("用户名已存在");
            }
            user.setUserId(deleted.getUserId());
            userMapper.restoreDeleted(user);
        } else {
            userMapper.insert(user);
        }
        // 仅写 default_group_id 不会出现在成员列表，必须同步补 group_members 记录。
        GroupMember uncategorized = GroupMember.builder()
                .groupId(1L).userId(user.getUserId()).role(GroupMember.ROLE_MEMBER).build();
        GroupMember existingMember = groupMemberMapper.selectIncludingDeleted(1L, user.getUserId());
        if (existingMember == null) {
            groupMemberMapper.insert(uncategorized);
        } else if (Boolean.TRUE.equals(existingMember.getDeleted())) {
            uncategorized.setId(existingMember.getId());
            groupMemberMapper.restoreDeleted(uncategorized);
        }
        return UserVO.from(user);
    }

    @Override
    @Transactional
    public UserVO updateUser(Long id, UserUpdateRequest req) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        String nextUsername = req.getUsername() != null ? req.getUsername() : user.getUsername();
        Integer nextRole = req.getRole() != null ? req.getRole() : user.getRole();
        validateRole(nextUsername, nextRole);
        // admin 必须保持固定用户名与超级管理员角色；改名或降级会破坏"唯一超级管理员"约束。
        if (User.SUPER_ADMIN_USERNAME.equals(user.getUsername())
                && (!User.SUPER_ADMIN_USERNAME.equals(nextUsername) || !User.isSuperAdmin(nextRole))) {
            throw new BusinessException("admin 是唯一超级管理员，不能改名或变更角色");
        }
        if (req.getUsername() != null) {
            if (!req.getUsername().equals(user.getUsername())
                    && userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) > 0) {
                throw new BusinessException("用户名已存在");
            }
            user.setUsername(req.getUsername());
        }
        if (req.getPassword() != null && !req.getPassword().isEmpty())
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        if (req.getNickname() != null) user.setNickname(req.getNickname());
        if (req.getPhone() != null) user.setPhone(req.getPhone());
        if (req.getEmail() != null) user.setEmail(req.getEmail());
        if (req.getSex() != null) user.setSex(req.getSex());
        if (req.getRole() != null) user.setRole(req.getRole());
        userMapper.updateById(user);
        return UserVO.from(user);
    }

    @Override
    @Transactional
    public void deleteUser(Long id, Long currentUserId) {
        if (currentUserId != null && currentUserId.equals(id)) {
            throw new BusinessException("不能删除自己的账号");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (User.SUPER_ADMIN_USERNAME.equals(user.getUsername())) {
            throw new BusinessException("唯一超级管理员 admin 不能删除");
        }
        // 删除账号时级联清理群成员、联系人和好友分组，避免残留指向已删除用户的记录。
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, id));
        contactMapper.delete(new LambdaQueryWrapper<Contact>().eq(Contact::getUserId, id)
                .or().eq(Contact::getContactUserId, id));
        friendGroupMapper.delete(new LambdaQueryWrapper<FriendGroup>().eq(FriendGroup::getOwnerId, id));
        // 位置与手机摄像头属于个人敏感数据，账号删除时同步逻辑删除。
        userLocationMapper.delete(new LambdaQueryWrapper<UserLocation>().eq(UserLocation::getUserId, id));
        locationHistoryMapper.delete(new LambdaQueryWrapper<LocationHistory>().eq(LocationHistory::getUserId, id));
        trajectorySessionMapper.delete(new LambdaQueryWrapper<TrajectorySession>().eq(TrajectorySession::getUserId, id));
        phoneCameraMapper.delete(new LambdaQueryWrapper<PhoneCamera>().eq(PhoneCamera::getUserId, id));
        userMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void batchMoveToGroup(Long fromGroupId, Long toGroupId) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .set(User::getGroupId, toGroupId)
                .eq(User::getGroupId, fromGroupId));
    }

    @Override
    public UserVO login(LoginRequest req) {
        return loginInternal(req, false);
    }

    @Override
    public UserVO loginBackend(LoginRequest req) {
        return loginInternal(req, true);
    }

    private UserVO loginInternal(LoginRequest req, boolean backendOnly) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("账号或密码错误");
        }
        if (backendOnly && (!User.SUPER_ADMIN_USERNAME.equals(user.getUsername())
                || !User.isSuperAdmin(user.getRole()))) {
            throw new BusinessException("只有超级管理员 admin 可以登录后台");
        }
        // 登录采用抢占式：密码通过即开启新会话，代际号 +1 使旧设备的 token 立即失效，不再因近期活动拒绝二次登录。
        long generation = loginSessions.startNewSession(user.getUserId());
        UserVO vo = UserVO.from(user);
        vo.setGeneration(generation);
        log.info("[Login] 登录成功（抢占式）: userId={}, username={}, generation={}",
                user.getUserId(), user.getUsername(), generation);
        return vo;
    }

    @Override
    public UserVO getProfile(Long userId) {
        return getUser(userId);
    }

    @Override
    @Transactional
    public UserVO updateProfile(Long userId, UserUpdateRequest req) {
        return updateUser(userId, req);
    }

    @Override
    @Transactional
    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isBlank() || newPassword == null || newPassword.isBlank()) {
            throw new BusinessException("原密码和新密码不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    @Override
    @Transactional
    public void switchGroup(Long userId, Long groupId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new BusinessException("群组不存在");
        }
        if (groupId.equals(user.getGroupId())) {
            return;
        }
        // 群主不能自行离开其管理的群，必须先在后台转让群主或解散群组。
        // 以 group_info.owner_id 为权威判据，避免成员表角色异常时绕过限制。
        if (groupMapper.selectCount(new LambdaQueryWrapper<Group>()
                .eq(Group::getOwnerId, userId)) > 0) {
            throw new BusinessException("群主不可更换群组，请先转让群主或解散群组");
        }
        // 目标群没有成员记录时自动补建，保证 group_members 与 default_group_id 一致。
        if (groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId)) == 0) {
            GroupMember member = GroupMember.builder()
                    .groupId(groupId).userId(userId).role(GroupMember.ROLE_MEMBER).build();
            GroupMember existing = groupMemberMapper.selectIncludingDeleted(groupId, userId);
            if (existing == null) {
                groupMemberMapper.insert(member);
            } else {
                member.setId(existing.getId());
                groupMemberMapper.restoreDeleted(member);
            }
        }
        // 切群必须删除其在其他群（含未分类组）的成员记录，否则用户会同时出现在多个群的成员列表中。
        List<GroupMember> others = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getUserId, userId)
                .ne(GroupMember::getGroupId, groupId));
        for (GroupMember m : others) {
            if (GroupMember.ROLE_OWNER.equals(m.getRole())) {
                throw new BusinessException("你是当前群组的群主，请先转让群主或解散群组");
            }
            groupMemberMapper.deleteById(m.getId());
        }
        user.setGroupId(groupId);
        userMapper.updateById(user);
        // 立即关闭旧群广播连接。客户端收到切组成功后会用新 groupId 重连，期间不会
        // 再接收旧群的文字、语音或位置广播。
        locationWebSocketHandler.closeUserSessions(userId);
    }

    /** 超级管理员角色与固定账号 admin 一一对应，其他账号不能持有 ROLE_SUPER_ADMIN。 */
    private void validateRole(String username, Integer role) {
        int value = role == null ? User.ROLE_USER : role;
        if (value != User.ROLE_USER && value != User.ROLE_SUPER_ADMIN) {
            throw new BusinessException("系统角色不合法");
        }
        if (User.isSuperAdmin(value) && !User.SUPER_ADMIN_USERNAME.equals(username)) {
            throw new BusinessException("只有固定账号 admin 可以是超级管理员");
        }
        if (User.SUPER_ADMIN_USERNAME.equals(username) && !User.isSuperAdmin(value)) {
            throw new BusinessException("admin 必须保持为唯一超级管理员");
        }
    }
}
