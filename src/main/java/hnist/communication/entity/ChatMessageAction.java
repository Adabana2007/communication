package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 按用户记录消息删除/撤回操作，供离线端同步。 */
@Data
@TableName("chat_message_actions")
public class ChatMessageAction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 会话类型：peer / group */
    private String chatType;
    private Long messageId;
    private String fp;
    private Long peerId;
    private Long groupId;
    /** 操作：delete / recall */
    private String action;
    private Long actorId;
    private String actorName;
    private LocalDateTime createdAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
