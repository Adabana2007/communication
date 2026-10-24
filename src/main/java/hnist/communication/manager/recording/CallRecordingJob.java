package hnist.communication.manager.recording;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 每次用户点击录制对应独立任务；磁盘 manifest 同时保存归属与恢复状态。 */
@Data
public class CallRecordingJob {
    private String id;
    private String requestId;
    private String roomId;
    private long ownerId;
    private long peerId;
    private String peerName = "";
    // 状态：starting/recording/processing/ready/failed，failed 可在素材完整时 retry 回 processing。
    private String state = "starting";
    private long createdAt;
    private long startedAt;
    private long stoppedAt;
    private long updatedAt;
    private double duration;
    private int limitSeconds = 3600;
    private long size;
    private String sha256 = "";
    private String md5 = "";
    private String message = "正在准备录制";
    private boolean interrupted;
    private boolean retryable;
    private String endReason = "";
    private List<Layout> layouts = new ArrayList<>();

    /** 画面布局切换事件，at 为相对录制起点的毫秒偏移。 */
    public record Layout(long at, boolean swapped, boolean mirror, boolean cameraOff) {}
    public record View(String id, String requestId, String roomId, String state, long startedAt,
                       long elapsedMs, double duration, int limitSeconds, long size, String sha256, String md5, String message,
                       boolean interrupted, String downloadUrl, String endReason, boolean retryable,
                       long peerId, String peerName, long createdAt) {}

    public View view() {
        long elapsed = startedAt == 0 ? 0 : Math.max(0, (stoppedAt == 0 ? System.currentTimeMillis() : stoppedAt) - startedAt);
        return new View(id, requestId, roomId, state, startedAt, elapsed, duration, limitSeconds, size, sha256, md5, message,
            interrupted, "ready".equals(state) ? "/api/call-recordings/" + id + "/file" : "", endReason, retryable, peerId, peerName, createdAt);
    }
}
