package hnist.communication.manager.recording.impl;

import hnist.communication.exception.BusinessException;
import hnist.communication.manager.recording.RecordingFileManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/** 以录制根目录为边界解析下载路径，阻断 ../ 目录穿越。 */
@Component
public class RecordingFileManagerImpl implements RecordingFileManager {

    private final String recordingPath;

    public RecordingFileManagerImpl(@Value("${recording.storage.path:E:/recordings}") String recordingPath) {
        this.recordingPath = recordingPath;
    }

    @Override
    public Resource resolve(String recordFileUrl) {
        if (recordFileUrl == null || recordFileUrl.isEmpty()) {
            throw new BusinessException("该通话无录制文件");
        }
        File baseDir;
        File file;
        try {
            baseDir = new File(recordingPath).getCanonicalFile();
            file = new File(baseDir, recordFileUrl).getCanonicalFile();
        } catch (IOException e) {
            throw new BusinessException("录制文件路径无效");
        }
        if (!file.toPath().startsWith(baseDir.toPath())) {
            throw new BusinessException("录制文件路径无效");
        }
        if (!file.exists() || !file.isFile()) {
            throw new BusinessException("录制文件不存在");
        }
        return new FileSystemResource(file);
    }
}
