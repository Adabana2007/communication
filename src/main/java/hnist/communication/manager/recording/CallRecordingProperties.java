package hnist.communication.manager.recording;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 通话录制的并发、超时与磁盘保留配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "call-recording")
public class CallRecordingProperties {
    private boolean enabled = true;
    private String storage = "./data/call-recordings";
    private String ffmpeg = "ffmpeg";
    private String ffprobe = "ffprobe";
    private String rtspBase = "rtsp://127.0.0.1:8554";
    private int maxConcurrent = 4;
    private int composeWorkers = 2;
    private int maxSeconds = 3600;
    private int retentionHours = 72;
    // 从创建任务到收到首帧的最长准备时间。
    private int prepareSeconds = 20;
    // 启动阶段重连采集进程的最大尝试次数。
    private int startupAttempts = 3;
    private int probeSeconds = 5;
    private int probeBytes = 8 * 1024 * 1024;
    // 采集进度超过该秒数没有前进即判定断流。
    private int stallSeconds = 30;
    private int composeSeconds = 1800;
    // 开始录制前要求的最小可用磁盘空间。
    private long minFreeBytes = 512L * 1024 * 1024;
}
