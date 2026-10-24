package hnist.communication.manager.recording;

import org.springframework.core.io.Resource;

/** 通话录制文件的路径安全校验与资源定位。 */
public interface RecordingFileManager {

    /** 将服务端保存的相对路径解析为录制根目录内的可下载文件。 */
    Resource resolve(String recordFileUrl);
}
