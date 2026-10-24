package hnist.communication.model;

import java.nio.file.Path;

/** 聊天媒体在磁盘上的文件描述，不映射数据库。 */
public record ChatMediaFile(Path path, long size, String extension) {
}
