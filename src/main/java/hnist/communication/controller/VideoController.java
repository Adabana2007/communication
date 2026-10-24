package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.common.Result;
import hnist.communication.dto.VideoRenameRequest;
import hnist.communication.model.VideoFile;
import hnist.communication.service.VideoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 视频文件列表、流式播放、重命名与删除。 */
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    @GetMapping
    public Result<Page<VideoFile>> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size,
                                         @RequestParam(required = false) String name) {
        return Result.success(videoService.listVideos(page, size, name));
    }

    @GetMapping("/{fileName}/stream")
    public ResponseEntity<Resource> stream(@PathVariable String fileName,
                                           HttpServletRequest request) {
        return videoService.streamVideo(fileName, request.getHeader("Range"));
    }

    @PutMapping("/{fileName}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> rename(@PathVariable String fileName,
                                @Valid @RequestBody VideoRenameRequest req) {
        videoService.renameVideo(fileName, req.getNewFileName());
        return Result.success();
    }

    @DeleteMapping("/{fileName}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable String fileName) {
        videoService.deleteVideo(fileName);
        return Result.success();
    }
}
