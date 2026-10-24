package hnist.communication.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 通话/会议忙碌标记请求
 */
@Data
public class CallBusyMarkRequest {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 忙碌类型：call=双人通话，conference=多人会议（缺省按 call 处理） */
    private String busyType = "call";
}
