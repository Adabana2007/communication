package hnist.communication.manager.camera.impl;

import hnist.communication.exception.BusinessException;
import hnist.communication.manager.camera.CameraDiagnostics;
import hnist.communication.manager.camera.CameraMediaGateway;
import hnist.communication.manager.camera.CameraMediaProperties;
import hnist.communication.manager.camera.CameraMediaUtils;
import hnist.communication.manager.camera.CameraStreamProbe;
import hnist.communication.entity.Camera;
import hnist.communication.mapper.CameraMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.*;

/** 采样原始 RTSP 与 MediaMTX 转发路径，缓存诊断结论供播放准备判定。 */
@Slf4j
@Service
public class CameraDiagnosticsImpl implements CameraDiagnostics {
    private record Entry(String fingerprint, Report report) {}
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final CameraMapper mapper;
    private final CameraMediaGateway gateway;
    private final CameraStreamProbe probe;
    private final CameraMediaProperties properties;
    private final ThreadPoolExecutor workers;

    public CameraDiagnosticsImpl(CameraMapper mapper, CameraMediaGateway gateway, CameraStreamProbe probe, CameraMediaProperties properties) {
        this.mapper = mapper; this.gateway = gateway; this.probe = probe; this.properties = properties;
        int count = Math.max(1, Math.min(4, properties.getProbeWorkers()));
        // 诊断任务不排队：SynchronousQueue 加 AbortPolicy 让满载请求直接失败，避免慢速 RTSP 源拖垮线程池。
        workers = new ThreadPoolExecutor(count, count, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().daemon(true).name("camera-diagnostic-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
    private String fingerprint(Camera c) {
        // 指纹包含 RTSP 地址与账号密码，只用于缓存命中判断，绝不返回给客户端。
        return Objects.toString(c.getRtspUrl(), "") + "\n" + c.getUsername() + "\n" + c.getPassword() + "\n" + c.getSourceProtocol() + "\n" + CameraMediaUtils.playbackPath(c);
    }
    @Override
    public Report current(Camera c) {
        Entry entry = entries.get(c.getId());
        return entry != null && entry.fingerprint().equals(fingerprint(c)) && entry.report().startedAt()
                .plusSeconds(Math.max(10, properties.getDiagnosticCacheSeconds())).isAfter(Instant.now()) ? entry.report() : null;
    }
    @Override
    public void invalidate(Long id) { entries.remove(id); }
    @Override
    public synchronized Report start(Long id) {
        Camera c = mapper.selectById(id);
        if (c == null) throw new BusinessException("摄像头不存在");
        Report cached = current(c);
        if (cached != null) return cached;
        // 先清理超过 120 秒的旧条目再检查容量，避免异常任务长期占用缓存。
        entries.entrySet().removeIf(e -> e.getValue().report().startedAt().plusSeconds(120).isBefore(Instant.now()));
        if (entries.size() >= 256) throw new BusinessException("诊断缓存已满，请稍后重试");
        String path = CameraMediaGateway.validPath(CameraMediaUtils.playbackPath(c));
        Report pending = new Report("running", Instant.now(), null, c.getMediamtxPath(), path, c.getSourceProtocol(), "unknown", null, null, "正在采样原始 RTSP 与实际播放路径（最长约 20 秒）");
        Entry entry = new Entry(fingerprint(c), pending);
        entries.put(id, entry);
        try { workers.execute(() -> {
          try {
            String configured = gateway.configuredTransport(c.getMediamtxPath());
            CameraStreamProbe.Sample original = probe.probe(CameraMediaUtils.sourceUrl(c), c.getSourceProtocol());
            CameraStreamProbe.Sample forwarded = probe.probe(gateway.rtspUrl(path), "tcp");
            Report done = new Report("completed", pending.startedAt(), Instant.now(), c.getMediamtxPath(), path,
                    c.getSourceProtocol(), configured, original, forwarded, conclusion(original, forwarded));
            entries.replace(id, entry, new Entry(entry.fingerprint(), done));
          } catch (Exception e) {
            // 采样异常统一收敛为 failed 报告，调用方轮询能看到终态而不是一直 running。
            log.warn("摄像头诊断失败: cameraId={}, reason={}", id, e.getMessage());
            Report failed = new Report("failed", pending.startedAt(), Instant.now(), c.getMediamtxPath(), path,
                    c.getSourceProtocol(), "unknown", null, null, "诊断未完成，请检查 RTSP 配置和诊断工具后重试");
            entries.replace(id, entry, new Entry(entry.fingerprint(), failed));
          }
        }); } catch (RejectedExecutionException e) {
            entries.remove(id, entry);
            log.warn("摄像头诊断队列已满: cameraId={}", id);
            throw new BusinessException("诊断任务已满，请稍后重试");
        }
        return pending;
    }
    private String conclusion(CameraStreamProbe.Sample a, CameraStreamProbe.Sample b) {
        if (!a.readable() && !b.readable()) return "原始源与转发源均未读通；先检查源地址、认证及服务端网络，勿直接启用转码";
        if (!b.readable()) return "原始源可读、播放路径不可读；检查 MediaMTX 路径、源配置及已有转换输出";
        if (b.needsCompatibility()) return "播放路径存在 H.265、B 帧或高位深编码特征；优先选择现有兼容输出或 H.264 子码流，必要时显式启用兼容输出";
        return "服务端播放路径可读；仍不出画时查看客户端 ICE、视频 RTP 与解码帧结果，不能仅据 VLC 可播放判定浏览器兼容";
    }
    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
