package hnist.communication.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频文件元数据（不映射数据库）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoFile {
    private String fileName;

    /** 文件大小（字节） */
    private long fileSize;
    private String format;
    /** 最后修改时间（毫秒时间戳） */
    private long lastModified;
}
