package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.dto.MemberAddRequest;
import hnist.communication.dto.MemberUpdateRequest;
import hnist.communication.vo.GroupMemberVO;

import java.util.List;

/** 群成员管理：批量添加/移除与角色变更。 */
public interface GroupMemberService {

    /** 批量添加成员（owner、admin 或超级管理员），用户自动从旧群迁移 */
    void addMembers(Long groupId, MemberAddRequest req);

    Page<GroupMemberVO> listMembers(Long groupId, int page, int size);

    /** 设/撤 admin 仅 owner 或超级管理员可操作，且不能修改 owner 的角色。 */
    void updateMember(Long groupId, Long userId, MemberUpdateRequest req);

    /** 不能移除 owner；被移除者归入未分类组。 */
    void removeMembers(Long groupId, Long operatorId, List<Long> userIds);
}
