package hnist.communication.vo;

import hnist.communication.entity.GroupMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMessageVO {
    private Long id;
    private Long groupId;
    /** 群组名称（非DB字段，查询时填充） */
    private String groupName;
    private Long senderId;
    private String senderName;
    private String content;
    /** 消息类型：text / image / video */
    private String msgType;
    private LocalDateTime createdAt;
    private String fp;
    private LocalDateTime recalledAt;
    private Long quoteMessageId;
    private MessageQuoteVO quote;

    public static GroupMessageVO from(GroupMessage msg) {
        return GroupMessageVO.builder()
                .id(msg.getId()).groupId(msg.getGroupId())
                .senderId(msg.getSenderId()).senderName(msg.getSenderName())
                .content(msg.getContent()).msgType(msg.getMsgType())
                .createdAt(msg.getCreatedAt())
                .fp(msg.getFp()).recalledAt(msg.getRecalledAt())
                .quoteMessageId(msg.getQuoteMessageId()).quote(msg.getQuote())
                .build();
    }
}
