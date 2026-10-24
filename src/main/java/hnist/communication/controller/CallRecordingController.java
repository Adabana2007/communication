package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.dto.CallRecordingLayoutRequest;
import hnist.communication.dto.CallRecordingPulseRequest;
import hnist.communication.dto.CallRecordingStartRequest;
import hnist.communication.dto.CallRecordingStopRequest;
import hnist.communication.manager.recording.CallMediaPresence;
import hnist.communication.manager.recording.CallRecordingJob;
import hnist.communication.service.CallRecordingService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

/** 通话录制任务的生命周期管理与录制文件下载。 */
@RestController
@RequestMapping("/api/call-recordings")
@RequiredArgsConstructor
public class CallRecordingController {
    private final CallRecordingService service;
    private final CallMediaPresence presence;

    @PostMapping("/health")
    public Result<CallMediaPresence.Health> health(@RequestBody CallRecordingPulseRequest body, Authentication auth) {
        return Result.success(presence.pulse((Long) auth.getPrincipal(), body.getRoomId(), body.getPeerId(), body.isCameraOff(), body.isMuted()));
    }
    /** 仅返回当前 JWT 用户自己的录制任务。 */
    @GetMapping
    public Result<List<CallRecordingJob.View>> list(Authentication auth) { return Result.success(service.list((Long) auth.getPrincipal())); }
    /** 开始录制；同一 owner + requestId 幂等重试返回已有任务，requestId 须为 8-80 位字母数字/下划线/连字符。 */
    @PostMapping
    public Result<CallRecordingJob.View> start(@RequestBody CallRecordingStartRequest body, Authentication auth) {
        return Result.success(service.start((Long) auth.getPrincipal(), body.getRoomId(), body.getPeerId(), body.getRequestId(), body.isSwapped(), body.isMirror(), body.isCameraOff()));
    }
    @GetMapping("/{id}")
    public Result<CallRecordingJob.View> get(@PathVariable String id, Authentication auth) { return Result.success(service.get((Long) auth.getPrincipal(), id)); }
    /** 停止录制；reason 缺省为 user_stop，传入 call_end 时服务端同时收尾双方任务。 */
    @PostMapping("/{id}/stop")
    public Result<CallRecordingJob.View> stop(@PathVariable String id, @RequestBody(required = false) CallRecordingStopRequest body, Authentication auth) {
        return Result.success(service.stop((Long) auth.getPrincipal(), id, body == null ? "user_stop" : body.getReason()));
    }
    @PostMapping("/{id}/layout")
    public Result<CallRecordingJob.View> layout(@PathVariable String id, @RequestBody CallRecordingLayoutRequest body, Authentication auth) {
        return Result.success(service.layout((Long) auth.getPrincipal(), id, body.isSwapped(), body.isMirror(), body.isCameraOff()));
    }
    @PostMapping("/{id}/retry")
    public Result<CallRecordingJob.View> retry(@PathVariable String id, Authentication auth) { return Result.success(service.retry((Long) auth.getPrincipal(), id)); }
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id, Authentication auth) throws IOException {
        var file = service.file((Long) auth.getPrincipal(), id);
        // 录制内容属隐私：private, no-store 禁止客户端与中间缓存留存。
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4")).contentLength(java.nio.file.Files.size(file))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"call_" + id + ".mp4\"")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(new FileSystemResource(file));
    }
}
