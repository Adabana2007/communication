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
@TableName("trajectory_sessions")
public class TrajectorySession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long userId;
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Double distanceKm;
    private Integer durationSec;
    private Integer pointCount;
    /** 0=录制中 1=已结束 */
    private Integer status;
    private LocalDateTime createdAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
