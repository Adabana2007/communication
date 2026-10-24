package hnist.communication.manager.camera.impl;

import hnist.communication.exception.BusinessException;
import hnist.communication.manager.camera.CameraCompatibility;
import hnist.communication.manager.camera.CameraCompatibilityUtils;
import hnist.communication.manager.camera.CameraDiagnostics;
import hnist.communication.manager.camera.CameraMediaGateway;
import hnist.communication.manager.camera.CameraMediaProperties;
import hnist.communication.manager.camera.CameraMediaUtils;
import hnist.communication.entity.Camera;
import hnist.communication.manager.camera.MediaToolLocator;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** 每个摄像头共享一个编码进程；compatible 显式启用或 direct 自动判定不支持时才启动。 */
@Slf4j
@Service
public class CameraCompatibilityImpl implements CameraCompatibility {
    private static final class Job {
        Process process; Path log; String path; Instant idleSince = Instant.now();
    }
    private final Map<Long, Job> jobs = new HashMap<>();
    /**
     * 每个摄像头最近一次“需要兼容输出”的结论，以及做结论时的源指纹。
     * 源暂时拉不到时沿用该结论继续准备兼容输出，避免把不可直连的地址丢给客户端；指纹不符即失效。
     */
    private final Map<Long, String> compatibilityVerdicts = new HashMap<>();
    private final CameraMediaProperties properties;
    private final CameraMediaGateway gateway;
    private final CameraDiagnostics diagnostics;
    private final MediaToolLocator locator;
    public CameraCompatibilityImpl(CameraMediaProperties properties, CameraMediaGateway gateway,
                                   CameraDiagnostics diagnostics, MediaToolLocator locator) {
        this.properties = properties; this.gateway = gateway; this.diagnostics = diagnostics; this.locator = locator;
    }

    @Override
    public synchronized void prepare(Camera camera) {
        if (!"compatible".equals(camera.getPlaybackMode())) return;
        if (!properties.isCompatibilityEnabled()) throw new BusinessException("服务端尚未开启按需兼容输出，请先复用现有 MediaMTX 输出路径");
        startJob(camera, CameraMediaUtils.playbackPath(camera), true);
    }

    /**
     * direct 模式的自动转码入口：调用前已由 WebrtcSupport 判定 WebRTC 不支持，
     * 判定本身就是证据，跳过"仅认 H.265/B 帧/高位深"的视频特征守卫。
     */
    @Override
    public synchronized void prepareAuto(Camera camera) {
        if (!properties.isCompatibilityEnabled()) {
            throw new BusinessException("该摄像头的音视频编码不被 WebRTC 支持，需要服务端开启 CAMERA_COMPATIBILITY_ENABLED 才能自动转码");
        }
        startJob(camera, CameraCompatibilityUtils.compatiblePath(camera), false);
    }

    /** 记住"该源需要兼容输出"，按源指纹校验，源参数变化自动失效。 */
    @Override
    public synchronized void remember(Camera camera) {
        compatibilityVerdicts.put(camera.getId(), CameraMediaUtils.sourceFingerprint(camera));
    }

    /** 是否已有与该摄像头当前源匹配的"需要兼容输出"结论。 */
    @Override
    public synchronized boolean remembered(Camera camera) {
        String fingerprint = compatibilityVerdicts.get(camera.getId());
        return fingerprint != null && fingerprint.equals(CameraMediaUtils.sourceFingerprint(camera));
    }

    /** 丢弃结论（改配置、删除摄像头、或当场判定为可直连时调用）。 */
    @Override
    public synchronized void forget(Camera camera) { compatibilityVerdicts.remove(camera.getId()); }

    private void startJob(Camera camera, String path, boolean requireDiagnosticEvidence) {
        Job running = jobs.get(camera.getId());
        // 同一摄像头只保留一个转码进程：进程还活着就只刷新空闲时间，避免重复拉起 ffmpeg。
        if (running != null && running.process.isAlive()) { running.idleSince = Instant.now(); return; }
        stop(camera.getId());
        if (requireDiagnosticEvidence) {
            CameraDiagnostics.Report report = diagnostics.current(camera);
            boolean approved = CameraMediaUtils.sourceFingerprint(camera).equals(camera.getCompatibilitySourceHash());
            // 人工启用前必须有证据：源指纹与已保存的 compatibilitySourceHash 一致，或诊断确认原始源需要兼容。
            if (!approved && (report == null || report.original() == null || !report.original().needsCompatibility()))
                throw new BusinessException("请先执行诊断；只有原始源确认存在 H.265、B 帧或高位深时才启用兼容输出");
        }
        if (jobs.size() >= Math.max(1, properties.getMaxTranscodes())) throw new BusinessException("兼容输出资源已满，请稍后重试或选择现有兼容路径");
        CameraMediaGateway.Snapshot snapshot = gateway.snapshot();
        CameraMediaGateway.PathState source = snapshot.paths().get(camera.getMediamtxPath());
        // 原始源必须已在 MediaMTX 就绪，否则 ffmpeg 拉不到流会立即失败退出。
        if (!snapshot.available() || source == null || !source.ready())
            throw new BusinessException("原始 MediaMTX 视频源尚未就绪，请等待源恢复后重新播放");
        Job job = new Job();
        job.path = path;
        try {
            gateway.ensurePublisher(job.path);
            job.log = Files.createTempFile("camera-compatible-", ".log");
            // 轨道名含 pcm 才追加 PCM 滤镜，普通源保持默认重采样。
            boolean pcm = source.trackInfos() != null && source.trackInfos().stream().anyMatch(t ->
                    t.codec() != null && t.codec().toLowerCase(Locale.ROOT).contains("pcm"));
            List<String> args = CameraCompatibilityUtils.command(locator.getFfmpegPath(), gateway.rtspUrl(camera.getMediamtxPath()), gateway.rtspUrl(job.path), pcm,
                    properties.isPcmLittleEndian(), properties.getPcmSampleRate());
            // 不记录含凭据的命令；日志文件仅供进程排障，停止时删除。
            job.process = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(job.log.toFile()).start();
            jobs.put(camera.getId(), job);
            gateway.invalidate();
        } catch (Exception e) {
            log.warn("兼容输出启动失败: cameraId={}, reason={}", camera.getId(), e.getMessage());
            if (job.log != null) {
                try {
                    Files.deleteIfExists(job.log);
                } catch (Exception deleteFailure) {
                    log.debug("兼容输出临时日志删除失败: {}", job.log.getFileName());
                }
            }
            throw new BusinessException("兼容输出启动失败，请检查服务端 ffmpeg 和 MediaMTX 配置");
        }
    }
    @Scheduled(fixedDelay = 5000)
    public synchronized void reap() {
        if (jobs.isEmpty()) return;
        CameraMediaGateway.Snapshot snapshot = gateway.snapshot();
        // 三类情况回收转码进程：进程已退出、日志超过 10MB、无人读取且空闲超过 idleSeconds。
        for (Long id : new ArrayList<>(jobs.keySet())) {
            Job job = jobs.get(id);
            CameraMediaGateway.PathState state = snapshot.paths().get(job.path);
            if (snapshot.available() && state != null && state.readers() > 0) job.idleSince = Instant.now();
            boolean logLimit = false;
            try {
                logLimit = Files.size(job.log) > 10 * 1024 * 1024;
            } catch (Exception e) {
                log.debug("读取兼容输出日志大小失败: cameraId={}", id);
            }
            if (!job.process.isAlive() || logLimit || (snapshot.available() && job.idleSince.plusSeconds(Math.max(30, properties.getIdleSeconds())).isBefore(Instant.now()))) stop(id);
        }
    }
    @Override
    public synchronized void stop(Long id) {
        Job job = jobs.remove(id);
        if (job == null) return;
        // 先 destroy 再限时等待，超时强杀；日志文件无论进程是否正常退出都删除。
        job.process.destroy();
        try { if (!job.process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { job.process.destroyForcibly(); job.process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS); } }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.process.destroyForcibly();
            log.debug("等待兼容输出进程退出的线程被中断: cameraId={}", id);
        }
        try {
            Files.deleteIfExists(job.log);
        } catch (Exception e) {
            log.debug("兼容输出临时日志删除失败: {}", job.log.getFileName());
        }
        gateway.invalidate();
    }
    @PreDestroy public synchronized void close() { new ArrayList<>(jobs.keySet()).forEach(this::stop); }
}
