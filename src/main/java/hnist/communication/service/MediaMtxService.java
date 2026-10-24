package hnist.communication.service;

/**
 * MediaMTX 流媒体服务客户端
 */
public interface MediaMtxService {

    /** 创建 path 从 RTSP 拉流；path 名由 cameraId 生成。 */
    String addPath(Long cameraId, String rtspUrl, String sourceProtocol);

    /** 配置已是最新则直接复用，否则重建；管理接口异常时不修改现有流配置。 */
    String ensurePath(Long cameraId, String rtspUrl, String sourceProtocol);

    /** 强制用最新地址和传输协议重建路径，用于修复僵尸会话或旧配置。 */
    String recreatePath(Long cameraId, String rtspUrl, String sourceProtocol);

    /** 删除失败只告警，不向上抛异常。 */
    void removePath(String pathName);

    /** 管理接口不可用时返回 false（按未就绪处理）。 */
    boolean isSourceReady(String pathName);

    /** 优先按当前请求 Host 推导 WebRTC 播放地址，便于客户端跨设备访问。 */
    String buildWebrtcUrl(String pathName);

    String buildHlsUrl(String pathName);

    String buildRtmpPushUrl(String pathName);

    /** WebRTC(WHIP) 推流地址：复用 WHEP 的 8889 端口，替代高延迟的 RTMP。 */
    String buildWhipPushUrl(String pathName);
}
