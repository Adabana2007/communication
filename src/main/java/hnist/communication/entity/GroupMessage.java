package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("group_messages")
public class GroupMessage {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;
    private Long senderId;
    /** 发送者名称（冗余存储，免联表） */
    private String senderName;
    private String content;
    /** 消息类型：text / image / video（默认 text） */
    private String msgType;
    private LocalDateTime createdAt;
    /** 客户端消息指纹，用于判重 */
    private String fp;
    private LocalDateTime recalledAt;
    private Long quoteMessageId;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private hnist.communication.vo.MessageQuoteVO quote;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
