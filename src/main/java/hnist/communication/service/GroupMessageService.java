package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.dto.GroupMessageRequest;
import hnist.communication.vo.GroupMessageVO;
import hnist.communication.vo.GroupUnreadVO;

import java.time.LocalDateTime;
import java.util.List;

/** 群聊消息发送、查询与未读统计。 */
public interface GroupMessageService {

    /** 仅群成员可发送，入库后实时推送给全群。 */
    GroupMessageVO send(GroupMessageRequest req);

    /** 仅群成员或超级管理员可查，按时间倒序分页。 */
    Page<GroupMessageVO> listByGroup(Long groupId, Long requesterId, int page, int size);

    /**
     * 列出用户所在的每个群及其未读数，只统计 since 之后他人的消息。
     * 未读必须由服务端按群成员关系列群：客户端本地标记无法枚举"从没点开过的群"。
     */
    List<GroupUnreadVO> listUnreadByUser(Long userId, LocalDateTime since);

    /** 后台分页查询所有群聊天记录（时间倒序），支持按群和关键字过滤。 */
    Page<GroupMessageVO> listAll(int page, int size, Long groupId, String keyword);

    /** 仅超级管理员可删除。 */
    void delete(Long id, Long operatorId);
}
