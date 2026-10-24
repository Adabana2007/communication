package hnist.communication.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LocationUpdateRequest {
    @NotNull(message = "用户ID不能为空")
    private Long userId;
    @NotNull(message = "纬度不能为空")
    private Double latitude;
    @NotNull(message = "经度不能为空")
    private Double longitude;
    private String address;
    private Double speed;
    private Double direction;
    private Double accuracy;
    private String sessionId;
    /** 客户端采集时间戳（毫秒）。为空时后端用服务器当前时间。
     *  轨迹点由采集器批量补传，用采集时间才能保证时长统计准确。 */
    private Long timestamp;
}
