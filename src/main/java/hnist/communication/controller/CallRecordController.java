package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.common.Result;
import hnist.communication.entity.CallRecord;
import hnist.communication.manager.recording.RecordingFileManager;
import hnist.communication.service.CallRecordService;
import hnist.communication.vo.CallRecordVO;
import hnist.communication.vo.CallStatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;

/** 通话记录查询、维护与录制文件下载。 */
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallRecordController {

    private final CallRecordService callRecordService;
    private final RecordingFileManager recordingFileManager;

    /** 普通用户仅能查询本人参与的通话，管理员可查看全部。 */
    @GetMapping
    public Result<Page<CallRecordVO>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size,
                                           @RequestParam(required = false) Integer mediaType,
                                           @RequestParam(required = false) Integer callType,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String startTime,
                                           @RequestParam(required = false) String endTime,
                                           Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return Result.success(callRecordService.list(page, size, mediaType, callType, status, keyword,
                startTime, endTime, (Long) authentication.getPrincipal(), admin));
    }

    /** 仅通话双方或管理员可查看详情。 */
    @GetMapping("/{id}")
    public Result<CallRecordVO> get(@PathVariable Long id, Authentication authentication) {
        CallRecordVO record = callRecordService.get(id);
        requireParticipantOrAdmin(record, authentication);
        return Result.success(record);
    }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CallStatsVO> stats() {
        return Result.success(callRecordService.stats());
    }

    @PostMapping
    public Result<CallRecordVO> create(@RequestBody CallRecord record, Authentication authentication) {
        // 主叫身份以 JWT 为准，禁止伪造他人的通话记录。
        record.setCallerId((Long) authentication.getPrincipal());
        // 录制文件路径只能由服务端/管理员维护，客户端不得借此构造任意文件下载路径。
        record.setRecordFileUrl(null);
        return Result.success(callRecordService.create(record));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CallRecordVO> update(@PathVariable Long id, @RequestBody CallRecord record) {
        return Result.success(callRecordService.update(id, record));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        callRecordService.delete(id);
        return Result.success();
    }

    @DeleteMapping("/batch")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> batchDelete(@RequestParam Long[] ids) {
        callRecordService.batchDelete(ids);
        return Result.success();
    }

    /** 下载录制文件；仅通话双方或管理员可访问。 */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, Authentication authentication) {
        CallRecordVO vo = callRecordService.get(id);
        requireParticipantOrAdmin(vo, authentication);
        Resource resource = recordingFileManager.resolve(vo.getRecordFileUrl());
        String fileName = resource.getFilename();
        String disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(resource);
    }

    private void requireParticipantOrAdmin(CallRecordVO record, Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        Long userId = (Long) authentication.getPrincipal();
        if (!admin && !userId.equals(record.getCallerId()) && !userId.equals(record.getCalleeId())) {
            throw new BusinessException("无权查看该通话记录");
        }
    }
}
