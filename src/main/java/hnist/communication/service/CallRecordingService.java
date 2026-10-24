package hnist.communication.service;

import hnist.communication.manager.recording.CallRecordingJob;

import java.nio.file.Path;
import java.util.List;

/** 通话双路录制服务。 */
public interface CallRecordingService {

    /** 创建或按请求编号返回已有录制任务。 */
    CallRecordingJob.View start(long owner, String room, long peer, String requestId,
                                boolean swapped, boolean mirror, boolean cameraOff);

    /** 查询本人拥有的录制任务。 */
    CallRecordingJob.View get(long owner, String id);

    /** 查询本人全部录制任务。 */
    List<CallRecordingJob.View> list(long owner);

    /** 停止录制，并使用默认原因记录结束状态。 */
    CallRecordingJob.View stop(long owner, String id);

    /** 停止录制并记录结束原因。 */
    CallRecordingJob.View stop(long owner, String id, String reason);

    /** 更新录制画面布局并重新生成成片。 */
    CallRecordingJob.View layout(long owner, String id, boolean swapped, boolean mirror, boolean cameraOff);

    /** 重试生成失败任务的成片。 */
    CallRecordingJob.View retry(long owner, String id);

    /** 返回本人可下载的成片路径。 */
    Path file(long owner, String id);
}
