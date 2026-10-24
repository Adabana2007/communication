package hnist.communication.service.impl;

import hnist.communication.exception.BusinessException;
import hnist.communication.model.ChatMediaFile;
import hnist.communication.service.ChatMediaService;
import hnist.communication.util.LimitedInputStream;
import hnist.communication.util.MediaFileValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 聊天媒体文件的上传校验、落盘、封面生成与读取。 */
@Slf4j
@Service
public class ChatMediaServiceImpl implements ChatMediaService {

    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> VIDEO_EXTS = Set.of("mp4", "3gp", "mov", "mkv");
    /** 语音消息扩展名：安卓录制 mp3，鸿蒙录制 m4a/aac。 */
    private static final Set<String> AUDIO_EXTS = Set.of("mp3", "m4a", "aac", "amr", "wav");
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 512L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 64L * 1024 * 1024;
    private static final int MAGIC_HEADER_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String uploadRoot;
    private final String ffmpegPath;

    public ChatMediaServiceImpl(@Value("${file.upload.path:E:/uploads}") String uploadRoot,
                                @Value("${media.ffmpeg.path:}") String ffmpegPath) {
        this.uploadRoot = uploadRoot;
        this.ffmpegPath = ffmpegPath;
    }

    @Override
    public Map<String, Object> upload(MultipartFile file, String mediaTypeHint) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        byte[] header;
        try (InputStream input = file.getInputStream()) {
            header = input.readNBytes(MAGIC_HEADER_BYTES);
        } catch (IOException e) {
            log.error("[chat-media] 读取上传文件头失败: {}", original, e);
            throw new BusinessException("读取上传文件失败");
        }
        // Android 高清相机的 content:// 文件名可能无扩展名；客户端类型提示仅作参考，
        // 服务端仍按白名单推断扩展名落盘，不做转码。
        if (!IMAGE_EXTS.contains(ext) && !VIDEO_EXTS.contains(ext) && !AUDIO_EXTS.contains(ext)) {
            ext = MediaFileValidator.detectExtension(header);
            if (ext.isEmpty()) {
                ext = inferExtension(file.getContentType(), mediaTypeHint);
            }
        }
        String mediaType;
        if (IMAGE_EXTS.contains(ext)) {
            mediaType = "image";
        } else if (VIDEO_EXTS.contains(ext)) {
            mediaType = "video";
        } else if (AUDIO_EXTS.contains(ext)) {
            mediaType = "audio";
        } else {
            throw new BusinessException("不支持的文件类型：" + (ext.isEmpty() ? "无扩展名" : ext));
        }
        if (!MediaFileValidator.matches(ext, header)) {
            throw new BusinessException("文件内容与扩展名不匹配");
        }
        long maxBytes = switch (mediaType) {
            case "image" -> MAX_IMAGE_BYTES;
            case "video" -> MAX_VIDEO_BYTES;
            default -> MAX_AUDIO_BYTES;
        };
        if (file.getSize() > maxBytes) {
            throw new BusinessException("媒体文件超过大小限制");
        }

        String mediaId = System.currentTimeMillis() + "-" + RANDOM.nextInt(100_000_000);
        // 聊天媒体只落磁盘不入库：视频可达上百 MB，大对象入库会撑爆 max_allowed_packet 与备份。
        Path dir = Paths.get(uploadRoot, "chat");
        Path target = null;
        try {
            Files.createDirectories(dir);
            target = dir.resolve(mediaId + "." + ext);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target);
            }
            // 视频抽首帧生成封面供聊天气泡展示：原生 video 组件放进消息列表会出现安卓黑块且首帧不渲染。
            if ("video".equals(mediaType)) {
                generateVideoThumb(target, dir.resolve(mediaId + "_thumb_hd.jpg"));
            }
        } catch (IOException e) {
            log.error("[chat-media] 保存文件失败: target={}", target, e);
            if (target != null) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupError) {
                    log.warn("[chat-media] 清理未完成的上传文件失败: {}", target, cleanupError);
                }
            }
            throw new BusinessException("媒体文件保存失败");
        }
        return Map.of(
                "mediaId", mediaId,
                "mediaType", mediaType,
                "url", "/api/chat/media/" + mediaId,
                "thumb", "/api/chat/media/" + mediaId + "/thumb");
    }

    @Override
    public Optional<ChatMediaFile> find(String mediaId) {
        File dir = new File(uploadRoot, "chat");
        File[] files = dir.listFiles((ignored, name) -> name.startsWith(mediaId + "."));
        if (files == null || files.length == 0) {
            return Optional.empty();
        }
        for (File file : files) {
            if (file.isFile()) {
                return Optional.of(toMediaFile(file));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<ChatMediaFile> thumbnail(String mediaId) {
        File dir = new File(uploadRoot, "chat");
        // 用新文件名 _thumb_hd 绕开旧 640px 封面和客户端一年缓存；旧文件保留以便回滚。
        File[] files = dir.listFiles((ignored, name) -> name.startsWith(mediaId + "_thumb_hd.")
                && new File(ignored, name).isFile());
        if (files == null || files.length == 0) {
            // 封面缺失（历史视频或上传时 ffmpeg 未就位）时首次请求现场抽帧；结果幂等，并发最坏重复执行一次。
            Optional<ChatMediaFile> video = find(mediaId);
            if (video.isPresent() && VIDEO_EXTS.contains(video.get().extension())) {
                File thumbFile = new File(dir, mediaId + "_thumb_hd.jpg");
                generateVideoThumb(video.get().path(), thumbFile.toPath());
                if (thumbFile.exists() && thumbFile.length() > 0) {
                    return Optional.of(toMediaFile(thumbFile));
                }
            }
            return Optional.empty();
        }
        File file = files[0];
        return file.isFile() ? Optional.of(toMediaFile(file)) : Optional.empty();
    }

    @Override
    public InputStreamResource open(ChatMediaFile file, long offset, long length) throws IOException {
        InputStream inputStream = new FileInputStream(file.path().toFile());
        try {
            inputStream.skipNBytes(offset);
            return new InputStreamResource(new LimitedInputStream(inputStream, length));
        } catch (IOException e) {
            try {
                inputStream.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /** 用 ffmpeg 抽取视频首帧作高清封面；未配置 ffmpeg 或抽帧失败时记录告警，不阻断上传。 */
    private void generateVideoThumb(Path video, Path thumb) {
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            log.warn("[chat-media] media.ffmpeg.path 未配置，跳过视频封面生成: {}", video.getFileName());
            return;
        }
        try {
            Process process = new ProcessBuilder(
                    ffmpegPath, "-y", "-ss", "0", "-i", video.toString(),
                    "-frames:v", "1", "-q:v", "2",
                    thumb.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("[chat-media] ffmpeg 抽帧超时(>20s)，已终止: {}", video.getFileName());
            } else if (process.exitValue() != 0) {
                log.warn("[chat-media] ffmpeg 抽帧失败: exit={}, file={}, ffmpeg={}",
                        process.exitValue(), video.getFileName(), ffmpegPath);
            }
        } catch (Exception e) {
            log.warn("[chat-media] ffmpeg 抽帧异常（检查 media.ffmpeg.path 是否存在）: ffmpeg={}, err={}",
                    ffmpegPath, e.getMessage());
        }
    }

    private ChatMediaFile toMediaFile(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return new ChatMediaFile(file.toPath(), file.length(), extension);
    }

    /** 文件名缺扩展名时按 MIME/类型提示映射到白名单扩展名，避免落盘任意后缀。 */
    private static String inferExtension(String contentType, String mediaTypeHint) {
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (mime.contains("quicktime")) return "mov";
        if (mime.contains("matroska")) return "mkv";
        if (mime.contains("3gpp")) return "3gp";
        if (mime.startsWith("video/")) return "mp4";
        if (mime.contains("png")) return "png";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("gif")) return "gif";
        if (mime.startsWith("image/")) return "jpg";
        if (mime.contains("mpeg")) return "mp3";
        if (mime.contains("mp4") || mime.contains("m4a")) return "m4a";
        if (mime.contains("aac")) return "aac";
        if (mime.contains("amr")) return "amr";
        if (mime.contains("wav")) return "wav";
        if ("video".equalsIgnoreCase(mediaTypeHint)) return "mp4";
        if ("image".equalsIgnoreCase(mediaTypeHint)) return "jpg";
        if ("audio".equalsIgnoreCase(mediaTypeHint)) return "m4a";
        return "";
    }
}
