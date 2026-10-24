package hnist.communication.dto;

import lombok.Data;

/** 结束轨迹会话的请求参数。 */
@Data
public class TrajectorySessionFinishRequest {
    private String sessionId;
    /** 客户端停止记录的时刻（毫秒时间戳），为空时后端使用当前时间。 */
    private Long endTime;
    /** 客户端计时器显示的录制时长（秒）；为空时按会话起止时间计算。 */
    private Integer durationSec;
}
