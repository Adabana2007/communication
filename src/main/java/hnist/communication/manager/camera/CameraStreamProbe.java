package hnist.communication.manager.camera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 有期限的真实解码采样，命令参数不经 shell；输出不包含源地址、凭证或 stderr。 */
@Slf4j
@Component
public class CameraStreamProbe {
    public record Track(String type, String codec, String profile, String pixelFormat,
                        int width, int height, String frameRate, int bFrames,
                        int sampleRate, String sampleFormat, int channels, String channelLayout) {}
    public record Sample(boolean readable, boolean videoFrame, String code, String message, List<Track> tracks) {
        public boolean needsCompatibility() {
            return readable && tracks.stream().anyMatch(t -> "video".equals(t.type()) &&
                    ("hevc".equals(t.codec()) || t.bFrames() > 0 || (t.pixelFormat() != null && t.pixelFormat().contains("10"))));
        }
    }
    private final CameraMediaProperties properties;
    private final MediaToolLocator locator;
    private final ObjectMapper json;
    public CameraStreamProbe(CameraMediaProperties properties, MediaToolLocator locator, ObjectMapper json) {
        this.properties = properties; this.locator = locator; this.json = json;
    }

    public Sample probe(String url, String transport) {
        Path output = null, error = null;
        Process process = null;
        try {
            output = Files.createTempFile("camera-probe-", ".json");
            error = Files.createTempFile("camera-probe-", ".log");
            List<String> command = new ArrayList<>(List.of(locator.getFfprobePath(), "-v", "error"));
            if ("tcp".equals(transport) || "udp".equals(transport)) command.addAll(List.of("-rtsp_transport", transport));
            command.addAll(List.of("-analyzeduration", "3000000", "-probesize", "1000000", "-read_intervals", "%+2",
                    "-show_entries", "stream=codec_type,codec_name,profile,pix_fmt,width,height,r_frame_rate,has_b_frames,sample_rate,sample_fmt,channels,channel_layout:frame=media_type",
                    "-of", "json", url));
            process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
            // 必须限时并强杀 ffprobe，否则无响应的 RTSP 源会一直占用诊断线程。
            if (!process.waitFor(Math.max(2, Math.min(10, properties.getProbeSeconds())), TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS);
                return failed("TIMEOUT", "采样超时，尚不能确认是网络、源等待还是解码问题");
            }
            if (process.exitValue() != 0) {
                // 只按 stderr 关键字归类成可操作结论，原始错误文本不返回给客户端。
                String message = Files.size(error) < 1048576 ? Files.readString(error) : "";
                if (message.contains("401 Unauthorized") || message.contains("403 Forbidden")) return failed("AUTH", "RTSP 认证失败或无访问权限");
                if (message.contains("404 Not Found")) return failed("NOT_FOUND", "RTSP 路径不存在或当前源尚未就绪");
                if (message.contains("Connection refused")) return failed("REFUSED", "目标拒绝连接，请检查地址、端口及服务");
                return failed("READ_FAILED", "RTSP 读取或解码失败，请结合流媒体日志确认原因");
            }
            // 输出超过上限视为异常，避免读入超大 JSON 占满内存。
            if (Files.size(output) > 1048576) return failed("OUTPUT_LIMIT", "采样输出超过限制");
            JsonNode root = json.readTree(output.toFile());
            List<Track> tracks = new ArrayList<>();
            for (JsonNode s : root.path("streams")) tracks.add(new Track(s.path("codec_type").asText(), s.path("codec_name").asText(),
                    s.path("profile").asText(), s.path("pix_fmt").asText(), s.path("width").asInt(), s.path("height").asInt(),
                    s.path("r_frame_rate").asText(), s.path("has_b_frames").asInt(),
                    s.path("sample_rate").asInt(), s.path("sample_fmt").asText(), s.path("channels").asInt(), s.path("channel_layout").asText()));
            boolean frame = false;
            for (JsonNode f : root.path("frames")) if ("video".equals(f.path("media_type").asText())) { frame = true; break; }
            return new Sample(!tracks.isEmpty(), frame, frame ? "OK" : "NO_FRAME_SAMPLE",
                    frame ? "采样已解码出视频帧" : "本次短采样未发现视频帧，不能据此判定永久不可播放", List.copyOf(tracks));
        } catch (java.io.IOException e) {
            log.warn("摄像头采样执行失败: {}", e.getMessage());
            return failed("PROBE_UNAVAILABLE", "诊断工具不可用，请检查服务端 ffprobe 配置");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("摄像头采样被中断");
            return failed("CANCELLED", "诊断已取消");
        } finally {
            // 无论成功、超时还是异常，都确保子进程退出并删除临时输出与日志。
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            for (Path file : new Path[]{output, error}) {
                if (file == null) continue;
                try {
                    Files.deleteIfExists(file);
                } catch (Exception e) {
                    log.debug("诊断临时文件删除失败: {}", file.getFileName());
                }
            }
        }
    }
    private Sample failed(String code, String message) { return new Sample(false, false, code, message, List.of()); }
}
