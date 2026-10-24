package hnist.communication.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.model.VideoFile;
import hnist.communication.service.VideoService;
import hnist.communication.util.HttpRangeUtils;
import hnist.communication.util.LimitedInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 本地视频目录的浏览、Range 播放与文件维护。 */
@Slf4j
@Service
public class VideoServiceImpl implements VideoService {

    private static final List<String> VIDEO_FORMATS =
            List.of("mp4", "avi", "mkv", "mov", "flv", "wmv", "webm");
    private final Path storagePath;

    public VideoServiceImpl(@Value("${video.storage.path}") String storagePath) {
        this.storagePath = Paths.get(storagePath).toAbsolutePath().normalize();
    }

    @Override
    public Page<VideoFile> listVideos(int page, int size, String name) {
        List<VideoFile> allFiles = new ArrayList<>();

        if (Files.exists(storagePath)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(storagePath)) {
                for (Path path : stream) {
                    // 只登记目录顶层的普通文件，子目录与非白名单扩展名直接忽略。
                    if (!Files.isRegularFile(path)) continue;
                    String fileName = path.getFileName().toString();
                    String ext = getExtension(fileName).toLowerCase();
                    if (!VIDEO_FORMATS.contains(ext)) continue;
                    if (name != null && !name.isEmpty()
                            && !fileName.toLowerCase().contains(name.toLowerCase())) continue;

                    allFiles.add(new VideoFile(
                            fileName,
                            Files.size(path),
                            ext,
                            Files.getLastModifiedTime(path).toMillis()
                    ));
                }
            } catch (IOException e) {
                throw new BusinessException("读取视频目录失败", e);
            }
        }

        // 文件系统没有分页接口：先按修改时间倒序再手工切分 fromIndex/toIndex。
        allFiles.sort((a, b) -> Long.compare(b.getLastModified(), a.getLastModified()));

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Page<VideoFile> result = new Page<>(safePage, safeSize);
        result.setTotal(allFiles.size());
        int fromIndex = (safePage - 1) * safeSize;
        if (fromIndex >= allFiles.size()) {
            result.setRecords(List.of());
            return result;
        }
        int toIndex = Math.min(fromIndex + safeSize, allFiles.size());
        result.setRecords(allFiles.subList(fromIndex, toIndex));
        return result;
    }

    @Override
    public ResponseEntity<Resource> streamVideo(String fileName, String rangeHeader) {
        Path filePath = storagePath.resolve(fileName).normalize();
        // normalize 后仍须位于 storagePath 下，拦截 fileName 中的 ../ 路径穿越。
        if (!Files.isRegularFile(filePath) || !filePath.startsWith(storagePath)) {
            throw new BusinessException("视频文件不存在");
        }

        try {
            long fileSize = Files.size(filePath);
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) contentType = "video/mp4";

            // 无 Range 时返回整文件 200；有 Range 时按 bytes 区间返回 206，供播放器拖动进度。
            if (rangeHeader == null) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .contentLength(fileSize)
                        .body(new FileSystemResource(filePath));
            }

            HttpRangeUtils.ByteRange range = HttpRangeUtils.parseSingleRange(rangeHeader, fileSize);
            if (range == null) {
                log.debug("非法的 HTTP Range 请求: fileName={}, range={}", fileName, rangeHeader);
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
                        .build();
            }
            long start = range.start();
            long end = range.end();
            long chunkSize = end - start + 1;

            InputStream inputStream = null;
            try {
                inputStream = Files.newInputStream(filePath);
                // 流式跳过前缀，避免把 start 之前的字节全部读入内存。
                inputStream.skipNBytes(start);
            } catch (IOException e) {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException closeError) {
                        e.addSuppressed(closeError);
                    }
                }
                throw new BusinessException("读取视频文件失败", e);
            }

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + fileSize)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .contentLength(chunkSize)
                    .body(new InputStreamResource(new LimitedInputStream(inputStream, chunkSize)));

        } catch (IOException e) {
            throw new BusinessException("读取视频文件失败", e);
        }
    }

    @Override
    public void renameVideo(String fileName, String newFileName) {
        Path source = storagePath.resolve(fileName).normalize();
        Path target = storagePath.resolve(newFileName).normalize();
        if (!Files.exists(source) || !source.startsWith(storagePath)) {
            throw new BusinessException("视频文件不存在");
        }
        if (!target.startsWith(storagePath)) {
            throw new BusinessException("非法文件名");
        }
        // 不覆盖已有目标文件：同名直接拒绝，避免误删目录中原有视频。
        if (Files.exists(target)) {
            throw new BusinessException("目标文件名已存在");
        }
        try {
            Files.move(source, target);
        } catch (IOException e) {
            throw new BusinessException("重命名失败", e);
        }
    }

    @Override
    public void deleteVideo(String fileName) {
        Path path = storagePath.resolve(fileName).normalize();
        if (!path.startsWith(storagePath)) {
            throw new BusinessException("非法文件名");
        }
        try {
            if (!Files.deleteIfExists(path)) {
                throw new BusinessException("视频文件不存在");
            }
        } catch (IOException e) {
            throw new BusinessException("删除失败", e);
        }
    }

    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1) : "";
    }

}
