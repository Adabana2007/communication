package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.model.ChatMediaFile;
import hnist.communication.service.ChatMediaService;
import hnist.communication.util.HttpRangeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** 聊天图片/视频/语音的上传与访问。 */
@Slf4j
@RestController
@RequestMapping("/api/chat/media")
@RequiredArgsConstructor
public class ChatMediaController {

    /** mediaId 只允许数字与连字符（防路径穿越）。 */
    private static final Pattern SAFE_ID = Pattern.compile("^[0-9-]{5,40}$");

    private final ChatMediaService chatMediaService;

    /** 上传聊天媒体，返回 mediaId 与访问 URL；扩展名经白名单校验后落盘。 */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mediaTypeHint", required = false) String mediaTypeHint) {
        return Result.success(chatMediaService.upload(file, mediaTypeHint));
    }

    /** 内网公开读取媒体，供图片/视频组件无鉴权头直载；支持 Range 断点播放。 */
    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> media(@PathVariable("id") String id,
                                                     @RequestHeader(value = "Range", required = false) String range) {
        if (!isSafeId(id)) {
            return ResponseEntity.badRequest().build();
        }
        Optional<ChatMediaFile> found = chatMediaService.find(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ChatMediaFile media = found.get();
        long total = media.size();
        long start = 0;
        long end = total - 1;
        boolean partial = false;

        if (range != null) {
            HttpRangeUtils.ByteRange byteRange = HttpRangeUtils.parseSingleRange(range, total);
            if (byteRange == null) {
                log.debug("[chat-media] 非法的 Range 请求: id={}, range={}", id, range);
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                        .build();
            }
            start = byteRange.start();
            end = byteRange.end();
            partial = true;
        }

        long length = end - start + 1;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentTypeOf(media.extension()));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentLength(length);
        if (partial) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }
        try {
            InputStreamResource body = chatMediaService.open(media, start, length);
            return new ResponseEntity<>(body, headers,
                    partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
        } catch (IOException e) {
            log.error("[chat-media] 读取媒体文件失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 视频封面读取；无封面时对仍存在的视频惰性抽帧，视频不存在则 404，客户端回退播放键。 */
    @GetMapping("/{id}/thumb")
    public ResponseEntity<InputStreamResource> thumb(@PathVariable("id") String id) {
        if (!isSafeId(id)) {
            return ResponseEntity.badRequest().build();
        }
        Optional<ChatMediaFile> found = chatMediaService.thumbnail(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ChatMediaFile file = found.get();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_JPEG);
            headers.setContentLength(file.size());
            headers.setCacheControl("public, max-age=31536000");
            return new ResponseEntity<>(chatMediaService.open(file, 0, file.size()), headers, HttpStatus.OK);
        } catch (IOException e) {
            log.error("[chat-media] 读取视频封面失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static boolean isSafeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    private static MediaType contentTypeOf(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "gif" -> MediaType.IMAGE_GIF;
            case "mp4" -> MediaType.parseMediaType("video/mp4");
            case "mov" -> MediaType.parseMediaType("video/quicktime");
            case "mkv" -> MediaType.parseMediaType("video/x-matroska");
            case "3gp" -> MediaType.parseMediaType("video/3gpp");
            case "mp3" -> MediaType.parseMediaType("audio/mpeg");
            case "m4a" -> MediaType.parseMediaType("audio/mp4");
            case "aac" -> MediaType.parseMediaType("audio/aac");
            case "amr" -> MediaType.parseMediaType("audio/amr");
            case "wav" -> MediaType.parseMediaType("audio/wav");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
