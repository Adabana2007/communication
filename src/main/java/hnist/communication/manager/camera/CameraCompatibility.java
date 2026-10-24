package hnist.communication.manager.camera;

import hnist.communication.entity.Camera;

/** 摄像头兼容输出编排服务。 */
public interface CameraCompatibility {

    /** 为显式启用 compatible 模式的摄像头准备兼容输出。 */
    void prepare(Camera camera);

    /** 为 WebRTC 不支持自动转码的摄像头准备兼容输出。 */
    void prepareAuto(Camera camera);

    /** 记住当前源需要兼容输出的判定。 */
    void remember(Camera camera);

    /** 判断当前源是否已有有效的兼容输出判定。 */
    boolean remembered(Camera camera);

    /** 丢弃指定摄像头的兼容输出判定。 */
    void forget(Camera camera);

    /** 停止指定摄像头的兼容输出进程。 */
    void stop(Long id);
}
