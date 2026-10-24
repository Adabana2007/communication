package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.common.Result;
import hnist.communication.dto.AlarmStatusRequest;
import hnist.communication.entity.Alarm;
import hnist.communication.service.AlarmService;
import hnist.communication.vo.AlarmVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 一键报警上报与后台报警管理。 */
@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    /** 上报报警；imageMediaIds/voiceMediaId 在同一事务内绑定到新报警。 */
    @PostMapping
    public Result<Alarm> create(@RequestBody Alarm alarm, Authentication authentication) {
        // 报警人只能来自已经校验过的 JWT，不能信任客户端传入的 userId。
        alarm.setUserId((Long) authentication.getPrincipal());
        return Result.success(alarmService.createWithMedia(
                alarm, alarm.getImageMediaIds(), alarm.getVoiceMediaId()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Page<AlarmVO>> list(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int size,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String start,
                                      @RequestParam(required = false) String end) {
        return Result.success(alarmService.list(page, size, userId, keyword, status, start, end));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody AlarmStatusRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long operatorId = auth != null && auth.getPrincipal() instanceof Long uid ? uid : null;
        alarmService.updateStatus(id, req.getStatus(), req.getRemark(), operatorId);
        return Result.success();
    }

    /** 将图片/语音写入 alarm_media（LONGBLOB），返回 mediaId 供上报时关联。 */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                               Authentication authentication) {
        var media = alarmService.storeMedia(file, (Long) authentication.getPrincipal());
        return Result.success(Map.of("mediaId", media.getId()));
    }

    /** 从数据库读取图片/语音；该 GET 对内网公开，供 <img>/<audio> 无鉴权头直载。 */
    @GetMapping("/media/{id}")
    public ResponseEntity<byte[]> media(@PathVariable Long id) {
        var m = alarmService.getMedia(id);
        if (m == null || m.getData() == null) {
            return ResponseEntity.notFound().build();
        }
        String mime = m.getMime() == null ? "application/octet-stream" : m.getMime();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).body(m.getData());
    }
}
