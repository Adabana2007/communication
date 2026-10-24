package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.ChatMessageAction;
import org.apache.ibatis.annotations.Insert;

public interface ChatMessageActionMapper extends BaseMapper<ChatMessageAction> {
    /** 唯一键命中已逻辑删除的记录时恢复该行，而不是静默丢弃本次操作。 */
    @Insert("INSERT INTO chat_message_actions " +
        "(user_id,chat_type,message_id,fp,peer_id,group_id,action,actor_id,actor_name,created_at,is_deleted) " +
        "VALUES (#{userId},#{chatType},#{messageId},#{fp},#{peerId},#{groupId},#{action},#{actorId},#{actorName},#{createdAt},0) " +
        "ON DUPLICATE KEY UPDATE is_deleted = 0")
    int insertOnce(ChatMessageAction action);
}
