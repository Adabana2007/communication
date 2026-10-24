package hnist.communication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GroupMessageRequest {
    @NotNull(message = "群组ID不能为空")
    private Long groupId;

    @NotNull(message = "发送者ID不能为空")
    private Long senderId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 500, message = "消息内容不能超过500字")
    private String content;

    /** 消息类型：text / image / video，缺省按 text 处理（图片/视频消息 content 存媒体 URL） */
    private String msgType;
    /** 客户端消息指纹，用于判重 */
    @Size(max = 100)
    private String fp;
    @jakarta.validation.constraints.Positive
    private Long quoteMessageId;
}
