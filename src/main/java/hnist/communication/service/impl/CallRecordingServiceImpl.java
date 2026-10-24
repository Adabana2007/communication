package hnist.communication.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.mapper.UserMapper;
import hnist.communication.manager.recording.CallMediaPresence;
import hnist.communication.manager.recording.CallRecordingJob;
import hnist.communication.manager.recording.CallRecordingMedia;
import hnist.communication.manager.recording.CallRecordingProperties;
import hnist.communication.service.CallRecordingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** 通话双路录制的任务编排、磁盘持久化与成片生成。 */
@Slf4j
@Service
public class CallRecordingServiceImpl implements CallRecordingService {
    private final CallRecordingProperties config;
    private final CallRecordingMedia media;
    private final CallMediaPresence presence;
    private final ObjectMapper json;
    private final UserMapper users;
    // jobs 保存可恢复的任务快照，captures 只保存仍在采集的运行态进程。
    private final Map<String, CallRecordingJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, Capture> captures = new ConcurrentHashMap<>();
    private ThreadPoolExecutor workers;
    private ThreadPoolExecutor lifecycleWorkers;
    private Path root;
    private volatile boolean closing;
    private static class Capture { volatile Process process; volatile long lastProgress = System.currentTimeMillis(); volatile long lastMediaTime = Long.MIN_VALUE; int attempt; }
    public CallRecordingServiceImpl(CallRecordingProperties config, CallRecordingMedia media, CallMediaPresence presence, ObjectMapper json, UserMapper users) {
        this.config = config; this.media = media; this.presence = presence; this.json = json; this.users = users;
    }

    @PostConstruct
    public void init() throws IOException {
        root = Path.of(config.getStorage()).toAbsolutePath().normalize(); Files.createDirectories(root); root = root.toRealPath();
        workers = new ThreadPoolExecutor(Math.max(1, config.getComposeWorkers()), Math.max(1, config.getComposeWorkers()), 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon(true).name("call-compose-", 0).factory());
        // 进程退出/停止属于短任务，独立线程池可避免等待 ffmpeg 收尾时占满录制合成线程。
        lifecycleWorkers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon(true).name("call-recording-lifecycle-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
        try (var folders = Files.list(root)) {
            for (Path folder : folders.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (!folder.getFileName().toString().matches("[a-f0-9-]{36}")) continue;
                try {
                    CallRecordingJob job = json.readValue(folder.resolve("job.json").toFile(), CallRecordingJob.class);
                    if (!folder.getFileName().toString().equals(job.getId())) continue;
                    // 重启恢复：未完成的任务一律标记 failed，并按原始素材是否完整决定能否 retry。
                    if (Set.of("starting", "recording", "processing").contains(job.getState())) {
                        job.setState("failed"); job.setRetryable(media.hasUsableCapture(folder));
                        job.setMessage(job.isRetryable() ? "录制服务已重启，原始素材已保留，可重试生成" : "录制服务已重启，未收到完整音视频，无法生成录像"); persist(job);
                    }
                    if ("failed".equals(job.getState())) {
                        job.setRetryable(media.hasUsableCapture(folder));
                        if (!job.isRetryable()) job.setMessage("未收到完整音视频，无法生成录像，请在通话媒体就绪后重新开始录制");
                    }
                    jobs.put(job.getId(), job);
                } catch (Exception e) { log.warn("[call-recording] manifest无法恢复: {}", folder.getFileName()); }
            }
        }
    }

    @Override
    public synchronized CallRecordingJob.View start(long owner, String room, long peer, String requestId,
                                                    boolean swapped, boolean mirror, boolean cameraOff) {
        CallMediaPresence.validate(owner, room, peer);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,80}")) throw new BusinessException("录制请求编号无效");
        for (var existing : jobs.values()) if (existing.getOwnerId() == owner && existing.getRequestId().equals(requestId)) {
            if (!existing.getRoomId().equals(room) || existing.getPeerId() != peer) throw new BusinessException("录制请求不匹配");
            return existing.view(); // HTTP 重试或桥重发不能创建第二个编码任务。
        }
        if (!config.isEnabled() || closing) throw new BusinessException("服务端录制暂不可用");
        if (captures.size() >= config.getMaxConcurrent()) throw new BusinessException("同时录制数量已满，请稍后重试");
        // 同一账号同时只允许一段活动录像，避免采集与合成任务叠加耗尽资源。
        if (jobs.values().stream().anyMatch(j -> j.getOwnerId() == owner && Set.of("starting", "recording", "processing").contains(j.getState())))
            throw new BusinessException("上一段录像正在录制或生成，请完成后再录制");
        // 媒体未就绪不创建任务，避免留下无法采集的空目录。
        presence.requireStreams(owner, room, peer);
        CallRecordingJob job = new CallRecordingJob(); job.setId(UUID.randomUUID().toString()); job.setOwnerId(owner); job.setPeerId(peer);
        fillPeerName(job);
        job.setRoomId(room); job.setRequestId(requestId); job.setCreatedAt(System.currentTimeMillis()); job.setUpdatedAt(job.getCreatedAt());
        job.setLimitSeconds(config.getMaxSeconds());
        job.getLayouts().add(new CallRecordingJob.Layout(0, swapped, mirror, cameraOff));
        Capture capture = new Capture();
        try {
            if (Files.getFileStore(root).getUsableSpace() < config.getMinFreeBytes()) throw new BusinessException("录像存储空间不足");
            Path directory = directory(job.getId()); Files.createDirectory(directory); persist(job);
            jobs.put(job.getId(), job); captures.put(job.getId(), capture);
            String base = config.getRtspBase().replaceAll("/+$", "");
            if (!base.matches("rtsps?://[^\\s?#]+")) throw new BusinessException("录制媒体地址配置无效");
            // 状态变更都在 job 锁内，与采集进度回调和进程退出回调互斥。
            synchronized (job) {
                launch(job, capture);
            }
            log.info("[call-recording] start id={} room={} owner={} peer={}", job.getId(), room, owner, peer);
            return job.view();
        } catch (BusinessException e) { captures.remove(job.getId()); media.stop(capture.process); fail(job, e.getMessage()); throw e; }
        catch (Exception e) { captures.remove(job.getId()); media.stop(capture.process); fail(job, "服务器无法启动录制，请检查媒体组件配置"); throw new BusinessException(job.getMessage()); }
    }

    private void launch(CallRecordingJob job, Capture capture) throws IOException {
        String base = config.getRtspBase().replaceAll("/+$", "");
        int attempt = ++capture.attempt;
        capture.lastMediaTime = Long.MIN_VALUE;
        Process process = media.capture(base + "/" + CallMediaPresence.path(job.getRoomId(), job.getOwnerId()),
            base + "/" + CallMediaPresence.path(job.getRoomId(), job.getPeerId()), directory(job.getId()),
            time -> progress(job, capture, attempt, time));
        capture.process = process;
        // 退出回调携带 attempt 校验，旧进程迟到的回调不会影响新进程。
        process.onExit().thenRun(() -> executeLifecycle(() -> captureExited(job, capture, process, attempt)));
    }

    private void captureExited(CallRecordingJob job, Capture capture, Process process, int attempt) {
        synchronized (job) {
            if (closing) return;
            // 只处理当前采集实例的退出；旧进程或已收尾的任务直接忽略。
            if (captures.get(job.getId()) != capture || capture.process != process || capture.attempt != attempt) return;
            if (!"starting".equals(job.getState())) { finish(job, capture, "media_ended", "通话媒体已结束，正在生成录像"); return; }
        }
        // 无有效片段的启动失败才允许重连；已收到的完整素材不能被 -y 覆盖。
        boolean usable = media.hasUsableCapture(directory(job.getId()));
        try {
            Thread.sleep(350);
            synchronized (job) {
                if (captures.get(job.getId()) != capture || capture.process != process || !"starting".equals(job.getState())) return;
                if (closing || usable || capture.attempt >= Math.max(1, Math.min(5, config.getStartupAttempts())) ||
                    System.currentTimeMillis() - job.getCreatedAt() >= config.getPrepareSeconds() * 1000L) {
                    finish(job, capture, "prepare_timeout", "录制未就绪，正在检查素材"); return;
                }
                presence.requireStreams(job.getOwnerId(), job.getRoomId(), job.getPeerId());
                // 旧尝试的素材改名保留，避免下一次采集被 -y 覆盖。
                for (String name : List.of("capture.log", "local.mkv", "remote.mkv")) {
                    Path file = directory(job.getId()).resolve(name);
                    if (Files.exists(file)) Files.move(file, file.resolveSibling("attempt-" + attempt + "-" + name));
                }
                job.setMessage("等待视频关键帧，正在重新准备录制（" + (attempt + 1) + "）"); persist(job);
                launch(job, capture);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); finish(job, capture, "prepare_timeout", "录制准备中断"); }
        catch (Exception e) { log.warn("[call-recording] startup retry failed id={}: {}", job.getId(), e.getMessage()); finish(job, capture, "prepare_timeout", "录制未就绪，正在检查素材"); }
    }

    private void progress(CallRecordingJob job, Capture capture, int attempt, long mediaTime) {
        synchronized (job) {
            if (closing || captures.get(job.getId()) != capture || capture.attempt != attempt || mediaTime <= 0 || capture.lastMediaTime == mediaTime ||
                !Set.of("starting", "recording").contains(job.getState())) return;
            capture.lastMediaTime = mediaTime; capture.lastProgress = System.currentTimeMillis();
            // 收到首个有效媒体进度才转为 recording；startedAt 之前的缓冲帧不会进入成片。
            if ("starting".equals(job.getState())) {
                if (System.currentTimeMillis() - job.getCreatedAt() > config.getPrepareSeconds() * 1000L) {
                    finish(job, capture, "prepare_timeout", "录制准备超时，正在检查素材"); return;
                }
                job.setStartedAt(System.currentTimeMillis()); job.setState("recording"); job.setMessage("录制中");
                log.info("[call-recording] recording id={} prepareMs={}", job.getId(), job.getStartedAt() - job.getCreatedAt());
                try { persist(job); } catch (BusinessException e) { abortCapture(job, capture); }
            }
        }
    }

    private void fillPeerName(CallRecordingJob job) {
        if (job.getPeerName() != null && !job.getPeerName().isBlank()) return;
        // 用户查询失败不阻断任务，昵称回落为“账号 id”。
        String name = "";
        try {
            var peer = users.selectById(job.getPeerId());
            if (peer != null) name = peer.getNickname() != null && !peer.getNickname().isBlank() ? peer.getNickname() : peer.getUsername();
        } catch (Exception e) { log.warn("[call-recording] 通话对象名称暂不可用 peer={}", job.getPeerId()); }
        job.setPeerName(name == null || name.isBlank() ? "账号 " + job.getPeerId() : name.trim());
    }

    @Override
    public CallRecordingJob.View get(long owner, String id) { var job = owned(owner, id); synchronized (job) { fillPeerName(job); return job.view(); } }
    @Override
    public List<CallRecordingJob.View> list(long owner) {
        return jobs.values().stream().filter(j -> j.getOwnerId() == owner).sorted(Comparator.comparingLong(CallRecordingJob::getCreatedAt).reversed())
            .limit(100).map(j -> { synchronized(j) { fillPeerName(j); return j.view(); } }).toList();
    }

    @Override
    public CallRecordingJob.View stop(long owner, String id) {
        return stop(owner, id, "user_stop");
    }

    @Override
    public CallRecordingJob.View stop(long owner, String id, String reason) {
        var job = owned(owner, id);
        if (reason == null || reason.isBlank()) reason = "user_stop";
        if (!Set.of("user_stop", "call_end", "media_error").contains(reason)) throw new BusinessException("录像停止原因无效");
        stopJob(job, reason);
        // 正常结束整通电话时，双方各自的录像都收尾；单独停止录像只影响本人的任务。
        if ("call_end".equals(reason)) for (var peerJob : jobs.values()) {
            if (!peerJob.getId().equals(id) && peerJob.getRoomId().equals(job.getRoomId()) &&
                peerJob.getOwnerId() == job.getPeerId() && peerJob.getPeerId() == owner) stopJob(peerJob, reason);
        }
        synchronized (job) { return job.view(); }
    }

    private void stopJob(CallRecordingJob job, String reason) {
        synchronized (job) {
            Capture capture = captures.get(job.getId());
            if (capture != null && Set.of("starting", "recording").contains(job.getState())) {
                finish(job, capture, reason, "正在生成录像");
            } else if ("media_ended".equals(job.getEndReason()) && System.currentTimeMillis() - job.getStoppedAt() <= 5000) {
                // 媒体先关闭、明确的停止意图稍后到达：仅修正这一类竞态，不覆盖持续断流/准备超时。
                job.setEndReason(reason); job.setInterrupted("media_error".equals(reason));
                if (!"failed".equals(job.getState())) job.setMessage("ready".equals(job.getState()) ?
                    (job.isInterrupted() ? "媒体异常中断，已生成收到的录像" : "录像已生成，可以保存到相册") : "正在生成录像");
                persist(job);
            }
        }
    }

    @Override
    public CallRecordingJob.View layout(long owner, String id, boolean swapped, boolean mirror, boolean cameraOff) {
        var job = owned(owner, id);
        synchronized (job) {
            if ("recording".equals(job.getState())) {
                // 仅 recording 状态接受切换；相同布局不重复写事件，事件数上限 3600。
                if (job.getLayouts().size() >= 3600) throw new BusinessException("画面切换过于频繁");
                var previous = job.getLayouts().getLast();
                if (previous.swapped() != swapped || previous.mirror() != mirror || previous.cameraOff() != cameraOff) {
                    job.getLayouts().add(new CallRecordingJob.Layout(System.currentTimeMillis(), swapped, mirror, cameraOff)); persist(job);
                }
            }
            return job.view();
        }
    }

    @Override
    public CallRecordingJob.View retry(long owner, String id) {
        var job = owned(owner, id);
        synchronized (job) {
            if ("failed".equals(job.getState())) {
                if (!job.isRetryable()) throw new BusinessException("没有完整音视频素材，请在通话中重新开始录制");
                job.setState("processing"); job.setMessage("正在重新生成录像"); persist(job); enqueue(job);
            }
            return job.view();
        }
    }

    private void finish(CallRecordingJob job, Capture capture, String reason, String message) {
        synchronized (job) {
            if (!Set.of("starting", "recording").contains(job.getState()) || captures.get(job.getId()) != capture) return;
            job.setStoppedAt(System.currentTimeMillis()); job.setEndReason(reason);
            job.setInterrupted(!Set.of("user_stop", "call_end", "limit").contains(reason)); job.setState("processing"); job.setMessage(message);
            try { persist(job); } catch (BusinessException e) { abortCapture(job, capture); return; }
        }
        // 先落库 processing 再异步停止采集；从未收到媒体且素材不可用时直接 fail。
        executeLifecycle(() -> {
            media.stop(capture.process);
            if (job.getStartedAt() == 0 && !media.hasUsableCapture(directory(job.getId()))) {
                fail(job, "未收到完整音视频，无法生成录像，请在通话媒体就绪后重新开始录制");
            } else { job.setRetryable(true); enqueue(job); }
            captures.remove(job.getId(), capture);
        });
    }
    private void abortCapture(CallRecordingJob job, Capture capture) {
        job.setState("failed"); job.setMessage("录像任务存储失败，已停止录制并保留素材"); captures.remove(job.getId(), capture);
        executeLifecycle(() -> media.stop(capture.process));
        log.error("[call-recording] 任务落盘失败，停止录制 id={}", job.getId());
    }

    /** 生命周期任务只做进程收尾，队列满时记录并降级到调用线程执行，保证采集进程不会失控。 */
    private void executeLifecycle(Runnable task) {
        if (lifecycleWorkers == null || lifecycleWorkers.isShutdown()) {
            task.run();
            return;
        }
        try {
            lifecycleWorkers.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("[call-recording] 生命周期队列繁忙，改由调用线程收尾");
            task.run();
        }
    }

    private void enqueue(CallRecordingJob job) {
        // 合成队列满时标记 failed 并保留素材，等待用户 retry。
        try { workers.execute(() -> compose(job)); }
        catch (RejectedExecutionException e) { fail(job, "录像生成队列已满，素材已保留，可重试生成"); }
    }

    private void compose(CallRecordingJob job) {
        try {
            Path directory = directory(job.getId()); var probe = media.compose(job, directory); Path result = directory.resolve("result.mp4");
            MessageDigest digest = MessageDigest.getInstance("SHA-256"), md5 = MessageDigest.getInstance("MD5");
            try (InputStream input = Files.newInputStream(result)) { byte[] bytes = new byte[64 * 1024]; int n; while ((n = input.read(bytes)) != -1) { digest.update(bytes, 0, n); md5.update(bytes, 0, n); } }
            synchronized (job) {
                job.setDuration(probe.duration()); job.setSize(Files.size(result)); job.setSha256(HexFormat.of().formatHex(digest.digest())); job.setMd5(HexFormat.of().formatHex(md5.digest())); job.setState("ready");
                job.setMessage(job.isInterrupted() ? "媒体提前结束，已生成收到的录像" : "录像已生成，可以保存到相册"); persist(job);
            }
            // 成片成功才删除原始素材；失败时保留 local.mkv/remote.mkv 供 retry 重新生成。
            Files.deleteIfExists(directory.resolve("local.mkv")); Files.deleteIfExists(directory.resolve("remote.mkv"));
            log.info("[call-recording] ready id={} duration={} bytes={} processingMs={} endReason={}", job.getId(), job.getDuration(), job.getSize(),
                System.currentTimeMillis() - job.getStoppedAt(), job.getEndReason());
        } catch (Exception e) {
            log.warn("[call-recording] compose failed id={}: {}", job.getId(), e.getMessage());
            job.setRetryable(media.hasUsableCapture(directory(job.getId())));
            fail(job, job.isRetryable() ? "录像生成失败，素材已保留，可重试生成" : "未收到完整音视频，无法生成录像，请在通话媒体就绪后重新开始录制");
        }
    }

    @Override
    public Path file(long owner, String id) {
        var job = owned(owner, id); if (!"ready".equals(job.getState())) throw new BusinessException("录像尚未生成");
        // 只允许下载 ready 任务的文件，NOFOLLOW_LINKS 防止符号链接逃逸出存储目录。
        Path file = directory(id).resolve("result.mp4"); if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new BusinessException("录像文件不存在"); return file;
    }

    private CallRecordingJob owned(long owner, String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new BusinessException("录像编号无效");
        var job = jobs.get(id); if (job == null || job.getOwnerId() != owner) throw new BusinessException("无权访问该录像或录像已过期"); return job;
    }
    private Path directory(String id) { return root.resolve(id); }
    private void fail(CallRecordingJob job, String message) {
        synchronized (job) { job.setState("failed"); job.setMessage(message); job.setUpdatedAt(System.currentTimeMillis()); if (jobs.containsKey(job.getId())) persist(job); }
    }
    private void persist(CallRecordingJob job) {
        try {
            // 先写临时文件再原子替换，避免进程中断留下半个 manifest；文件系统不支持时退化为普通替换。
            job.setUpdatedAt(System.currentTimeMillis()); Path temp = directory(job.getId()).resolve("job.json.tmp"); json.writeValue(temp.toFile(), job);
            try { Files.move(temp, directory(job.getId()).resolve("job.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, directory(job.getId()).resolve("job.json"), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new BusinessException("录像任务保存失败", e); }
    }

    @Scheduled(fixedDelay = 2000)
    public void checkCaptures() {
        long now = System.currentTimeMillis();
        // 定时兜底三类结束：准备超时、采集进度停滞、达到时长上限。
        captures.forEach((id, capture) -> {
            var job = jobs.get(id); if (job == null) return;
            if ("starting".equals(job.getState()) && now - job.getCreatedAt() > config.getPrepareSeconds() * 1000L)
                finish(job, capture, "prepare_timeout", "录制准备超时，正在检查素材");
            else if ("recording".equals(job.getState()) && now - capture.lastProgress > config.getStallSeconds() * 1000L)
                finish(job, capture, "media_stall", "媒体持续中断，正在保存收到的录像");
            else if (job.getStartedAt() > 0 && now - job.getStartedAt() >= config.getMaxSeconds() * 1000L)
                finish(job, capture, "limit", "已达到本段录像时长上限，正在生成文件");
        });
    }

    @Scheduled(fixedDelay = 60000)
    public void expire() {
        long cutoff = System.currentTimeMillis() - Math.max(1, config.getRetentionHours()) * 3600_000L;
        jobs.forEach((id, job) -> { synchronized (job) {
            if (job.getUpdatedAt() >= cutoff || !Set.of("ready", "failed").contains(job.getState())) return;
            // 只清理超过保留期且已终态的任务；先删目录内文件再删目录。
            try {
                Path folder = directory(id); try (var files = Files.list(folder)) { for (Path file : files.toList()) if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.delete(file); }
                Files.delete(folder); jobs.remove(id, job);
            } catch (IOException e) { log.warn("[call-recording] 清理延后 id={}", id); }
        }});
    }

    @PreDestroy
    public void close() {
        closing = true;
        captures.values().forEach(c -> media.stop(c.process));
        shutdownPool(workers, "call-compose");
        shutdownPool(lifecycleWorkers, "call-recording-lifecycle");
    }

    private void shutdownPool(ThreadPoolExecutor pool, String name) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[call-recording] {}线程池未在5秒内退出，执行强制关闭", name);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
