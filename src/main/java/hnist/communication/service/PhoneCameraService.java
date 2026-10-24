package hnist.communication.service;

import hnist.communication.vo.PhoneCameraVO;

import java.util.List;
import java.util.Map;

/** 手机摄像头共享：设备注册、推流信令与查看状态查询。 */
public interface PhoneCameraService {
    /** 设备注册（WebSocket 连接后首次消息）；同一设备换号登录时复用记录。 */
    void registerDevice(String deviceId, Long userId, String deviceName);
    /** 刷新最后心跳时间。 */
    void updateHeartbeat(String deviceId);
    /** WebSocket 断开时调用，同时清空播放地址并移除查看者。 */
    void markOffline(String deviceId);
    /** 列表展示时按通讯录在线状态和心跳超时修正，不回写数据库。 */
    List<PhoneCameraVO> listAll();
    /** 管理员请求推流，返回 rtmpUrl/whipUrl 等要发给手机的信令。 */
    Map<String, Object> prepareStream(Long id);
    /** 手机确认推流成功；已停止或被拒绝的会话不会再被改回推流中。 */
    void confirmStreamReady(String deviceId);
    /** 手机上报推流失败；message 会随 /view-status 透传给查看方。 */
    void reportStreamError(String deviceId, String message);

    /** 兼容旧调用（无失败原因）。 */
    default void reportStreamError(String deviceId) {
        reportStreamError(deviceId, "");
    }
    /** 被查看方拒绝（授权弹窗拒绝/摄像头权限未授权）。 */
    void rejectStreamByDevice(String deviceId);
    /** 查看状态聚合：offline/stopped/rejected/streaming/waiting，供查看方轮询。 */
    Map<String, Object> getViewStatus(Long id);
    /** 停止推流并返回 deviceId，供 WebSocket 通知手机。 */
    String stopStream(Long id);
    /** 手机主动停止共享；上报设备由已认证的信令会话确定。 */
    void stopStreamByDevice(String deviceId);
    /** 查询设备的流是否已到达 MediaMTX（供后台轮询播放就绪状态）。 */
    boolean isStreamReady(Long id);

    // ---- 查看者心跳（请求者退出 → 自动结束推流） ----

    /** 请求推流时登记查看者。 */
    void registerViewer(Long cameraId, Long userId, String username);

    /** 播放页定时调用以续约。 */
    void touchViewer(Long cameraId);

    /** 显式停止推流时清除查看者记录。 */
    void clearViewer(Long cameraId);

    /** 停止所有心跳超时查看者对应的推流，返回被停止的设备 deviceId 列表。 */
    List<String> stopStaleStreams();
}
