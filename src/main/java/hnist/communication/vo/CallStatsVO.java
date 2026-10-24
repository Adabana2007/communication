package hnist.communication.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallStatsVO {
    private long totalCount;
    private long todayCount;
    /** 视频通话占比（百分比） */
    private double videoRatio;
    /** 平均通话时长（秒） */
    private double avgDuration;
}
