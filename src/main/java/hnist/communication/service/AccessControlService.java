package hnist.communication.service;

import org.springframework.security.core.Authentication;

import java.util.Set;

/** 统一的资源访问判定接口。 */
public interface AccessControlService {

    /** 判断当前认证信息是否包含管理员角色。 */
    boolean hasAdminRole(Authentication authentication);

    /** 获取当前登录用户 ID，认证失效时抛出业务异常。 */
    Long currentUserId(Authentication authentication);

    /** 要求访问者为目标用户本人或管理员。 */
    void requireSelfOrAdmin(Long targetUserId, Authentication authentication, String message);

    /** 要求访问者为群成员或管理员。 */
    void requireGroupMemberOrAdmin(Long groupId, Authentication authentication, String message);

    /** 要求访问者与目标用户同群，或访问者为目标用户本人/管理员。 */
    void requireSameGroupOrAdmin(Long targetUserId, Authentication authentication, String message);

    /** 判断用户是否属于指定群组。 */
    boolean isGroupMember(Long groupId, Long userId);

    /** 判断两个用户是否属于同一群组。 */
    boolean isSameGroup(Long firstUserId, Long secondUserId);

    /** 返回与指定用户同群的用户 ID 集合（含本人）。 */
    Set<Long> sameGroupUserIds(Long userId);
}
