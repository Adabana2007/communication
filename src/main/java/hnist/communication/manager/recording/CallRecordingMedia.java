package hnist.communication.manager.recording;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hnist.communication.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/** 不经过 shell；录制源只由服务端配置和已校验的会话/用户 ID 组成。 */
@Slf4j
@Component
public class CallRecordingMedia {
    private final CallRecordingProperties config;
    private final ObjectMapper json;
    private final ThreadPoolExecutor progressReaders;
    // 新版 FFmpeg 用 -/filter_complex 从文件读滤镜；运行失败后缓存降级为 -filter_complex_script。
    private volatile String filterOption = "-/filter_complex";
    public CallRecordingMedia(CallRecordingProperties config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        this.progressReaders = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon(true).name("call-progress-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public record Probe(double start, double duration, boolean video, boolean audio, String videoCodec, String audioCodec, int width, int height) {
        public Probe(double start, double duration, boolean video, boolean audio, String videoCodec, String audioCodec) {
            this(start, duration, video, audio, videoCodec, audioCodec, 1280, 720);
        }
    }

    public Process capture(String local, String remote, Path directory, LongConsumer progress) throws IOException {
        // 两路分别落盘 local.mkv/remote.mkv，保留到达服务器的原始时间线，合成阶段再统一裁剪对齐。
        List<String> command = new ArrayList<>(List.of(config.getFfmpeg(), "-hide_banner", "-loglevel", "warning", "-y", "-copyts"));
        input(command, local); input(command, remote);
        command.addAll(List.of("-map", "0:v:0", "-map", "0:a:0", "-c", "copy", "-avoid_negative_ts", "disabled",
            directory.resolve("local.mkv").toString(), "-map", "1:v:0", "-map", "1:a:0", "-c", "copy", "-avoid_negative_ts", "disabled",
            "-progress", "pipe:1", "-stats_period", "0.5", directory.resolve("remote.mkv").toString()));
        Process process = new ProcessBuilder(command).redirectError(directory.resolve("capture.log").toFile()).start();
        // 进度行只用于判断采集是否停滞；解析失败忽略，不影响录制进程。
        try {
            progressReaders.execute(() -> readProgress(process, progress));
        } catch (RejectedExecutionException e) {
            log.warn("录制进度读取队列已满，本次进度回调跳过");
        }
        return process;
    }

    private void readProgress(Process process, LongConsumer progress) {
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            long frames = 0;
            while ((line = reader.readLine()) != null) {
                try {
                    if (line.startsWith("frame=")) {
                        frames = Long.parseLong(line.substring(6).trim());
                    }
                    if (frames > 0 && line.startsWith("out_time_us=")) {
                        progress.accept(Long.parseLong(line.substring(12)));
                    }
                } catch (NumberFormatException e) {
                    log.debug("忽略无法解析的录制进度行: {}", line);
                }
            }
        } catch (IOException e) {
            // 进程退出时管道关闭属于正常收尾，不能污染业务错误。
            log.debug("录制进度管道已关闭: {}", e.getMessage());
        }
    }

    private void input(List<String> command, String source) {
        // 到达同一服务器的 wall-clock 时间作为两路公共时间线；不分别归零，否则会丢失起始偏移。
        if (source.startsWith("rtsp://") || source.startsWith("rtsps://"))
            command.addAll(List.of("-rtsp_transport", "tcp", "-timeout", "10000000", "-analyzeduration", String.valueOf(Math.max(1, config.getProbeSeconds()) * 1_000_000L),
                "-probesize", String.valueOf(Math.max(1048576, config.getProbeBytes())), "-fpsprobesize", "0", "-use_wallclock_as_timestamps", "1"));
        else command.add("-re"); // 非 RTSP 输入按原媒体时间戳节流；生产入口始终构造 RTSP URL。
        command.addAll(List.of("-i", source));
    }

    public void stop(Process process) {
        if (process == null || !process.isAlive()) return;
        // 先向 ffmpeg stdin 写 q 让其正常收尾封装，超时才 destroy，最后强杀。
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write("q\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            if (!process.waitFor(6, TimeUnit.SECONDS)) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
        } catch (Exception e) {
            process.destroyForcibly();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("停止录制进程失败，已强制结束: {}", e.getMessage());
        }
    }

    public Probe probe(Path file) throws IOException {
        Path report = file.resolveSibling(file.getFileName() + ".probe.json");
        run(List.of(config.getFfprobe(), "-v", "error", "-show_streams", "-show_format", "-of", "json", file.toString()), report,
            file.resolveSibling(file.getFileName() + ".probe.log"), 30);
        JsonNode root = json.readTree(report.toFile()), format = root.path("format");
        boolean video = false, audio = false; String vc = "", ac = ""; int width = 0, height = 0;
        for (JsonNode stream : root.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) { video = true; vc = stream.path("codec_name").asText(); width = stream.path("width").asInt(); height = stream.path("height").asInt(); }
            if ("audio".equals(stream.path("codec_type").asText())) { audio = true; ac = stream.path("codec_name").asText(); }
        }
        return new Probe(format.path("start_time").asDouble(0), format.path("duration").asDouble(0), video, audio, vc, ac, width, height);
    }

    /** 只有两路都含音视频且共同片段不少于 0.25 秒时才视为可重试；仅有容器头返回 false。 */
    public boolean hasUsableCapture(Path directory) {
        try {
            Path localFile = directory.resolve("local.mkv"), remoteFile = directory.resolve("remote.mkv");
            if (!Files.isRegularFile(localFile) || !Files.isRegularFile(remoteFile) ||
                Files.size(localFile) < 1024 || Files.size(remoteFile) < 1024) return false;
            Probe local = probe(localFile), remote = probe(remoteFile);
            double end = Math.min(local.duration > local.start ? local.duration : local.start + local.duration,
                remote.duration > remote.start ? remote.duration : remote.start + remote.duration);
            return local.video && local.audio && remote.video && remote.audio && local.width > 0 && local.height > 0 &&
                remote.width > 0 && remote.height > 0 && end - Math.max(local.start, remote.start) >= .25;
        } catch (Exception e) {
            log.warn("录制素材可用性检查失败: directory={}, reason={}",
                    directory.getFileName(), e.getMessage());
            return false;
        }
    }

    public Probe compose(CallRecordingJob job, Path directory) throws IOException {
        Probe local = probe(directory.resolve("local.mkv")), remote = probe(directory.resolve("remote.mkv"));
        if (!local.video || !remote.video || !local.audio || !remote.audio) throw new BusinessException("双方音视频数据未录齐，原始素材已保留");
        double origin = Math.max(local.start, remote.start);
        // Matroska duration 在非零起始时间的文件上可能包含起始时间；统一换成终点。
        double localEnd = local.duration > local.start ? local.duration : local.start + local.duration;
        double remoteEnd = remote.duration > remote.start ? remote.duration : remote.start + remote.duration;
        double end = Math.min(localEnd, remoteEnd);
        // RTSP 到达时间是 epoch；只保留服务器确认“录制中”到停止的区间，不把准备阶段缓冲帧计入成片。
        if (origin > 100_000_000) {
            if (job.getStartedAt() > 0) origin = Math.max(origin, job.getStartedAt() / 1000.0);
            if (job.getStoppedAt() > 0) end = Math.min(end, job.getStoppedAt() / 1000.0);
        }
        double duration = end - origin;
        if (!Double.isFinite(duration) || duration < .25 || duration > config.getMaxSeconds() + 60)
            throw new BusinessException("录像时间戳异常，原始素材已保留");
        List<CallRecordingJob.Layout> events = normalizedLayouts(job, origin, duration);
        // 以首个布局事件决定基准画面，另一路等比放大后裁切，最终限制在 720p 短边/1280 长边内。
        boolean initiallySwapped = !job.getLayouts().isEmpty() && job.getLayouts().getFirst().swapped();
        Probe preferred = initiallySwapped ? local : remote, other = initiallySwapped ? remote : local;
        if (preferred.width < 2 || preferred.height < 2) throw new BusinessException("录像分辨率无效");
        double upscale = Math.max(1, Math.min((double) other.width / preferred.width, (double) other.height / preferred.height));
        double w = preferred.width * upscale, h = preferred.height * upscale;
        double scale = Math.min(1, Math.min(720 / Math.min(w, h), 1280 / Math.max(w, h)));
        int width = Math.max(2, (int) (w * scale / 2) * 2), height = Math.max(2, (int) (h * scale / 2) * 2);
        String filter = filter(events, origin, duration, width, height);
        Path filterFile = directory.resolve("compose.filters"); Files.writeString(filterFile, filter, StandardCharsets.UTF_8);
        Path part = directory.resolve("result.part.mp4"), output = directory.resolve("result.mp4");
        String option = filterOption;
        List<String> command = new ArrayList<>(List.of(config.getFfmpeg(), "-hide_banner", "-loglevel", "warning", "-y", "-copyts",
            "-i", directory.resolve("local.mkv").toString(), "-i", directory.resolve("remote.mkv").toString(),
            "-filter_complex_threads", "1", option, filterFile.toString(), "-map", "[vout]", "-map", "[aout]",
            "-t", number(duration), "-c:v", "libx264", "-threads", "2", "-preset", "veryfast", "-crf", "20",
            "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-r", "30", "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart", part.toString()));
        try { run(command, directory.resolve("compose.stdout"), directory.resolve("compose.log"), config.getComposeSeconds()); }
        catch (IOException failure) {
            Path log = directory.resolve("compose.log");
            if (!option.equals("-/filter_complex") || !Files.exists(log) || Files.size(log) > 10000 || !Files.readString(log).contains("Unrecognized option")) throw failure;
            // 新版 FFmpeg 已移除 filter_complex_script，默认走 -/filter_complex；旧版不识别该选项时回退。
            command.set(command.indexOf(option), "-filter_complex_script"); filterOption = "-filter_complex_script";
            run(command, directory.resolve("compose.stdout"), log, config.getComposeSeconds());
        }
        Probe result = probe(part);
        if (!"h264".equals(result.videoCodec) || !"aac".equals(result.audioCodec) || result.duration < .25 ||
            Math.abs(result.duration - duration) > 1.0 || Files.size(part) < 1024)
            throw new BusinessException("录像输出校验失败，原始素材已保留");
        // ffprobe 只读封装不能证明每个包可解码；再执行完整解码检查。
        run(List.of(config.getFfmpeg(), "-v", "error", "-xerror", "-i", part.toString(), "-f", "null", "-"),
            directory.resolve("verify.stdout"), directory.resolve("verify.log"), config.getComposeSeconds());
        // 只有校验和完整解码都通过，才把 result.part.mp4 改名为可下载的 result.mp4。
        Files.move(part, output, StandardCopyOption.REPLACE_EXISTING);
        return result;
    }

    static List<CallRecordingJob.Layout> normalizedLayouts(CallRecordingJob job, double origin, double duration) {
        // 布局事件按相对 origin 的毫秒偏移归一化，同一时刻只保留最后一个事件，并保证首帧从 0 开始。
        List<CallRecordingJob.Layout> out = new ArrayList<>();
        for (CallRecordingJob.Layout event : job.getLayouts()) {
            long base = origin > 100_000_000 ? Math.round(origin * 1000) : job.getStartedAt();
            long offset = event.at() == 0 ? 0 : Math.max(0, event.at() - base);
            if (offset < duration * 1000) {
                CallRecordingJob.Layout adjusted = new CallRecordingJob.Layout(offset, event.swapped(), event.mirror(), event.cameraOff());
                if (!out.isEmpty() && out.getLast().at() == offset) out.set(out.size() - 1, adjusted); else out.add(adjusted);
            }
        }
        if (out.isEmpty() || out.getFirst().at() != 0) out.addFirst(new CallRecordingJob.Layout(0, false, false, false));
        return out;
    }

    static String filter(List<CallRecordingJob.Layout> events, double origin, double duration, int width, int height) {
        // 每路只解码/缩放一次，split 后构造两种大小窗布局；enable 时间线跟随本机切换。
        String mirror = intervals(events, duration, 1), camera = intervals(events, duration, 2);
        String swap = intervals(events, duration, 0);
        int smallW = Math.max(16, (int)Math.round(Math.min(width * .22, height * .30))), smallH = (int)Math.round(smallW * 4.0 / 3);
        int margin = Math.max(4, (int)Math.round(Math.min(width, height) * .03));
        String large = width + ":" + height, small = smallW + ":" + smallH;
        String videoTrim = "trim=start=" + number(origin) + ":end=" + number(origin + duration) + ",";
        String audioTrim = "atrim=start=" + number(origin) + ":end=" + number(origin + duration) + ",";
        return "[0:v]" + videoTrim + "setpts=PTS-" + number(origin) + "/TB,hflip=enable='" + mirror +
            "',drawbox=color=0x122c4c:t=fill:enable='" + camera + "',split=2[lb0][ls0];" +
            "[lb0]scale=" + large + ":force_original_aspect_ratio=increase,crop=" + large + ",setsar=1[lb];" +
            "[ls0]scale=" + small + ":force_original_aspect_ratio=increase,crop=" + small + ",setsar=1[ls];" +
            "[1:v]" + videoTrim + "setpts=PTS-" + number(origin) + "/TB,split=2[rb0][rs0];" +
            "[rb0]scale=" + large + ":force_original_aspect_ratio=increase,crop=" + large + ",setsar=1[rb];" +
            "[rs0]scale=" + small + ":force_original_aspect_ratio=increase,crop=" + small + ",setsar=1[rs];" +
            "[rb][ls]overlay=x=W-w-" + margin + ":y=" + margin + ":shortest=1[normal];" +
            "[lb][rs]overlay=x=W-w-" + margin + ":y=" + margin + ":shortest=1[swapped];" +
            "[normal][swapped]overlay=x=0:y=0:shortest=1:enable='" + swap + "',fps=30:start_time=0,format=yuv420p[vout];" +
            "[0:a]" + audioTrim + "asetpts=PTS-" + number(origin) + "/TB,aresample=async=1:first_pts=0[a0];" +
            "[1:a]" + audioTrim + "asetpts=PTS-" + number(origin) + "/TB,aresample=async=1:first_pts=0[a1];" +
            "[a0][a1]amix=inputs=2:duration=shortest:normalize=1[aout]";
    }

    private static String intervals(List<CallRecordingJob.Layout> events, double duration, int field) {
        // 生成 ffmpeg enable 表达式；没有任何有效区间时返回 0 禁用该效果。
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            boolean enabled = field == 0 ? event.swapped() : field == 1 ? event.mirror() : event.cameraOff();
            if (enabled) {
                double start = event.at() / 1000.0, end = i + 1 < events.size() ? events.get(i + 1).at() / 1000.0 : duration + 1;
                terms.add("gte(t," + number(start) + ")*lt(t," + number(end) + ")");
            }
        }
        return terms.isEmpty() ? "0" : String.join("+", terms);
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.6f", value); }

    private void run(List<String> command, Path stdout, Path stderr, int seconds) throws IOException {
        // 外部进程必须限时；超时强杀并转成 IOException，由调用方决定保留素材还是失败。
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("媒体处理超时"); }
            if (process.exitValue() != 0) throw new IOException("媒体处理失败，请检查 " + stderr.getFileName());
        } catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("媒体处理已取消", e); }
    }

    @PreDestroy
    public void close() {
        progressReaders.shutdownNow();
    }
}
