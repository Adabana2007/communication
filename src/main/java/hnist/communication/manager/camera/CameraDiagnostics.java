package hnist.communication.manager.camera;

import hnist.communication.entity.Camera;

import java.time.Instant;

/** 摄像头诊断服务：采样原始 RTSP 与转发路径并缓存结论。 */
public interface CameraDiagnostics {

    /** 单次诊断的不可变结果快照。 */
    record Report(String state, Instant startedAt, Instant completedAt, String sourcePath, String playbackPath,
                  String requestedTransport, String configuredTransport,
                  CameraStreamProbe.Sample original, CameraStreamProbe.Sample forwarded, String conclusion) {
    }

    /** 返回与摄像头当前源配置匹配且未过期的诊断结果。 */
    Report current(Camera camera);

    /** 清除指定摄像头的诊断缓存。 */
    void invalidate(Long id);

    /** 异步启动诊断，已存在有效缓存时直接返回缓存结果。 */
    Report start(Long id);
}
