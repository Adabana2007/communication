package hnist.communication.manager.camera;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 摄像头媒体地址、诊断工具与兼容转码的部署配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "camera-media")
public class CameraMediaProperties {
    private String rtspBase = "rtsp://127.0.0.1:8554";
    private String ffprobe = "ffprobe";
    private String ffmpeg = "ffmpeg";
    private int probeSeconds = 8;
    private int probeWorkers = 2;
    private int diagnosticCacheSeconds = 60;
    // 必须由部署人员显式开启；普通连接超时不能触发转码。
    private boolean compatibilityEnabled = false;
    private int maxTranscodes = 2;
    private int idleSeconds = 30;

    /**
     * 播放准备（/play）的最长等待；到期后按 MediaMTX 真实状态返回 ready/not_ready，
     * 不把可用性不明的直连地址交给客户端而导致 404 或协商失败。
     */
    private long playReadyTimeoutMs = 6000;

    /**
     * 私有小端 16bit PCM 源开关：MediaMTX 按 PT 11 静态定义把它当大端 L16 转发，下游会解成满量程噪声。
     * 默认关闭，标准 L16 源本身就是大端，只有确认源为私有小端 PCM 时才打开。
     */
    private boolean pcmLittleEndian = false;

    /** 私有小端 PCM 的真实采样率，转码时按此值重新标注并重采样（MediaMTX 会误标为 44100）。 */
    private int pcmSampleRate = 16000;
}
