package hnist.communication.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import hnist.communication.entity.Group;
import hnist.communication.entity.GroupMember;
import hnist.communication.entity.User;
import hnist.communication.mapper.GroupMapper;
import hnist.communication.mapper.GroupMemberMapper;
import hnist.communication.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动时创建或修正 admin 账号、收敛管理员角色，并可选修复群归属数据。 */
@Slf4j
@Component
@Order(-100)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    /** BCrypt 密码哈希的固定前缀：存量密码不以它开头则视为非 BCrypt，启动时重新编码。 */
    private static final String BCRYPT_PREFIX = "$2a$";
    /** 唯一超级管理员账号 */
    private static final String ADMIN_USERNAME = User.SUPER_ADMIN_USERNAME;
    /** 未分类组ID（与 GroupServiceImpl 保持一致） */
    private static final long UNCATEGORIZED_GROUP_ID = 1L;

    private final UserMapper userMapper;
    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${security.bootstrap-admin.password:}")
    private String bootstrapAdminPassword;

    @Value("${data-initializer.heal-group-consistency-enabled:false}")
    private boolean healGroupConsistencyEnabled;

    @Override
    public void run(String... args) {
        User admin = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, ADMIN_USERNAME));
        if (admin == null && bootstrapAdminPassword != null && !bootstrapAdminPassword.isBlank()) {
            User deleted = userMapper.selectByUsernameIncludingDeleted(ADMIN_USERNAME);
            if (deleted != null && Boolean.TRUE.equals(deleted.getDeleted())) {
                User restored = User.builder()
                        .userId(deleted.getUserId())
                        .username(ADMIN_USERNAME)
                        .password(passwordEncoder.encode(bootstrapAdminPassword))
                        .nickname("超级管理员")
                        .role(User.ROLE_SUPER_ADMIN)
                        .groupId(UNCATEGORIZED_GROUP_ID)
                        .build();
                userMapper.restoreDeleted(restored);
                ensureMembership(restored.getUserId());
                admin = restored;
                log.info("[INIT] 已恢复逻辑删除的管理员账号");
            }
        }
        if (admin == null) {
            // 未提供初始化密码时不创建默认 admin/admin：固定默认口令等同于公开后门。
            if (bootstrapAdminPassword == null || bootstrapAdminPassword.isBlank()) {
                log.warn("[INIT] 未检测到管理员账号，且未设置 BOOTSTRAP_ADMIN_PASSWORD；为安全起见不自动创建 admin/admin");
            } else {
                admin = User.builder()
                        .username(ADMIN_USERNAME)
                        .password(passwordEncoder.encode(bootstrapAdminPassword))
                        .nickname("超级管理员").role(User.ROLE_SUPER_ADMIN).groupId(UNCATEGORIZED_GROUP_ID).build();
                userMapper.insert(admin);
                // 管理员同样补一条未分类组的成员记录：系统按 group_members 维护用户与群的归属关系。
                ensureMembership(admin.getUserId());
                log.info("[INIT] 管理员账号已按 BOOTSTRAP_ADMIN_PASSWORD 创建；请登录后立即轮换密码");
            }
        } else {
            boolean changed = false;
            if (!admin.getPassword().startsWith(BCRYPT_PREFIX)) {
                admin.setPassword(passwordEncoder.encode(admin.getPassword()));
                changed = true;
                log.info("[INIT] 管理员密码已升级为BCrypt加密");
            }
            if (admin.getNickname() == null) {
                admin.setNickname("超级管理员");
                changed = true;
            }
            if (!User.isSuperAdmin(admin.getRole())) {
                admin.setRole(User.ROLE_SUPER_ADMIN);
                changed = true;
            }
            if (admin.getGroupId() == null) {
                admin.setGroupId(UNCATEGORIZED_GROUP_ID);
                changed = true;
            }
            if (changed) userMapper.updateById(admin);
        }

        // 只有固定账号 admin 可以拥有后台权限：其他 role=1 或历史 role=2 的账号统一改回 ROLE_USER，
        // 保证系统始终只有一个超级管理员。
        int demoted = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .set(User::getRole, User.ROLE_USER)
                .in(User::getRole, User.ROLE_SUPER_ADMIN, 2)
                .ne(User::getUsername, ADMIN_USERNAME));
        if (demoted > 0) {
            log.warn("[INIT] 已将 {} 个非 admin 管理账号调整为普通用户", demoted);
        }
        if (healGroupConsistencyEnabled) {
            healGroupConsistency();
        } else {
            log.info("[INIT] 群归属自动修复已关闭（HEAL_GROUP_CONSISTENCY_ENABLED=false）");
        }
    }

    /**
     * 以 users.default_group_id 为唯一真源修复 group_members：空值归入未分类组、缺失记录补齐、残留记录删除。
     * 该修复会删除与真源不符的成员记录，因此由 heal-group-consistency-enabled 控制，默认关闭。
     */
    private void healGroupConsistency() {
        // ① 空值兜底
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .set(User::getGroupId, UNCATEGORIZED_GROUP_ID)
                .isNull(User::getGroupId));

        List<User> users = userMapper.selectList(null);
        int removed = 0;
        int added = 0;
        for (User u : users) {
            Long gid = u.getGroupId();
            if (gid == null) {
                continue;
            }
            // ③ 删除不属于当前默认群的残留成员记录
            removed += groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getUserId, u.getUserId())
                    .ne(GroupMember::getGroupId, gid));
            // ② 默认群缺成员记录时补齐（群本身必须存在）
            Long exists = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, gid)
                    .eq(GroupMember::getUserId, u.getUserId()));
            if (exists == 0) {
                Group g = groupMapper.selectById(gid);
                if (g != null) {
                    ensureMembership(gid, u.getUserId());
                    added++;
                } else {
                    log.warn("[INIT] 群归属自愈：用户 {} 的 default_group_id={} 不存在对应群，跳过补录", u.getUserId(), gid);
                }
            }
        }
        if (removed > 0 || added > 0) {
            log.info("[INIT] 群归属自愈完成: removed={}, added={}", removed, added);
        }
    }

    /** 启动自愈与管理员恢复都必须复用逻辑删除行，避免 uk_group_user 冲突。 */
    private void ensureMembership(Long userId) {
        ensureMembership(UNCATEGORIZED_GROUP_ID, userId);
    }

    private void ensureMembership(Long groupId, Long userId) {
        GroupMember member = GroupMember.builder()
                .groupId(groupId).userId(userId).role(GroupMember.ROLE_MEMBER).build();
        GroupMember existing = groupMemberMapper.selectIncludingDeleted(groupId, userId);
        if (existing == null) {
            groupMemberMapper.insert(member);
        } else if (Boolean.TRUE.equals(existing.getDeleted())) {
            member.setId(existing.getId());
            groupMemberMapper.restoreDeleted(member);
        }
    }
}
