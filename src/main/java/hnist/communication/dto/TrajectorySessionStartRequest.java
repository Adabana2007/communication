package hnist.communication.dto;

import lombok.Data;

/** 开始轨迹会话的请求参数。 */
@Data
public class TrajectorySessionStartRequest {
    /** 兼容字段，实际以 JWT 身份为准。 */
    private Long userId;
    private String sessionId;
    /** 客户端点击记录的时刻（毫秒时间戳），为空时后端使用当前时间。 */
    private Long startTime;
}
