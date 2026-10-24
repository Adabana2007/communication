package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.model.VideoFile;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

/** 视频文件列表、Range 播放与文件维护。 */
public interface VideoService {

    /** 按文件名模糊搜索并分页。 */
    Page<VideoFile> listVideos(int page, int size, String name);

    /** 支持 HTTP Range 断点续传播放。 */
    ResponseEntity<Resource> streamVideo(String fileName, String rangeHeader);

    void renameVideo(String fileName, String newFileName);

    void deleteVideo(String fileName);
}
