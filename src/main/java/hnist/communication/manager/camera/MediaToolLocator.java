package hnist.communication.manager.camera;

import hnist.communication.CommunicationApplication;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解析 ffmpeg/ffprobe 的实际路径，避免把部署机路径写死。
 * 顺序为环境变量、显式配置、运行目录与 jar 目录下的 ffmpeg/、PATH；找不到只在转码或诊断时报错，不影响直连摄像头。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaToolLocator {

    private final CameraMediaProperties properties;

    private volatile String ffmpegPath;
    private volatile String ffprobePath;

    @PostConstruct
    void resolve() {
        ffmpegPath = resolve("ffmpeg", "CAMERA_FFMPEG", properties.getFfmpeg());
        ffprobePath = resolve("ffprobe", "CAMERA_FFPROBE", properties.getFfprobe());
        log.info("媒体工具解析结果: ffmpeg={}, ffprobe={}", ffmpegPath, ffprobePath);
    }

    public String getFfmpegPath() { return ffmpegPath; }

    public String getFfprobePath() { return ffprobePath; }

    private String resolve(String tool, String envKey, String configured) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            warnIfMissing(envKey + " 指向的 " + tool, Path.of(env.trim()));
            return env.trim();
        }
        if (configured != null && !configured.isBlank()
                && (configured.contains("/") || configured.contains("\\") || configured.contains("."))) {
            // 含路径分隔符或扩展名才视为显式路径，裸命令名继续走约定目录和 PATH。
            warnIfMissing("配置的 " + tool, Path.of(configured));
            return configured;
        }
        // 约定相对位置：<运行目录|jar目录>/ffmpeg/<tool>[.exe]
        for (Path candidate : conventionalCandidates(tool)) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        // PATH 上的裸命令名，交给进程启动时解析
        return configured != null && !configured.isBlank() ? configured : tool;
    }

    private List<Path> conventionalCandidates(String tool) {
        String exeName = isWindows() ? tool + ".exe" : tool;
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("ffmpeg", exeName).toAbsolutePath().normalize());
        Path jarDir = jarDirectory();
        if (jarDir != null) {
            candidates.add(jarDir.resolve("ffmpeg").resolve(exeName));
        }
        return candidates;
    }

    private Path jarDirectory() {
        try {
            Path location = Path.of(CommunicationApplication.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location) ? location.getParent() : location;
        } catch (Exception e) {
            log.debug("定位程序目录失败，跳过 jar 同目录候选: {}", e.getMessage());
            return null;
        }
    }

    private void warnIfMissing(String label, Path path) {
        if (!Files.isRegularFile(path)) {
            log.warn("{}: {} 不存在；需要转码/诊断时将失败，请检查配置", label, path);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

