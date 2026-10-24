package hnist.communication.vo;

import lombok.*;

/** 由服务端原消息生成的一层引用；撤回/缺失时不返回正文或媒体地址。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MessageQuoteVO {
    private Long messageId;
    private String fp;
    private Long senderId;
    private String senderName;
    private String msgType;
    private String content;
    private String status;
}
