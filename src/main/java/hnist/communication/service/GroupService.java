package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.dto.GroupCreateRequest;
import hnist.communication.dto.GroupUpdateRequest;
import hnist.communication.vo.GroupVO;

/** 群组创建、查询、成员归组与解散。 */
public interface GroupService {

    /** 创建群组（名称不可重复），创建者自动成为 owner */
    GroupVO createGroup(GroupCreateRequest req);

    /** 分页查询群组列表，支持按名称搜索 */
    Page<GroupVO> listGroups(int page, int size, String name);

    /** 仅 owner 可解散；未分类组不可删除，成员自动归入未分类组。idOrName 支持群组 ID 或名称。 */
    void deleteGroup(String idOrName, Long operatorId);

    void moveUserToGroup(Long userId, Long groupId);

    GroupVO getGroupById(Long id);

    /** 仅 owner 或超级管理员可更新。 */
    GroupVO updateGroup(Long id, GroupUpdateRequest req, Long operatorId);

    /** 将用户从当前所属群移除，归入未分类组。 */
    void removeUserFromCurrentGroup(Long userId);
}
