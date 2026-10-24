package hnist.communication.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 群未读概览：按服务端群成员关系列出所有群及未读数，避免客户端漏算红点。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupUnreadVO {
    private Long groupId;
    private String groupName;
    /** 该群的未读数（晚于 since、且不是自己发的） */
    private Integer unreadCount;
    /** 该群最近一条消息的时间；该群没有任何消息时为 null */
    private LocalDateTime lastMessageAt;
}
