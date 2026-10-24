package hnist.communication.service;

import hnist.communication.dto.CameraRequest;
import hnist.communication.vo.CameraVO;

import java.util.List;

/** 摄像头接入与播放：ONVIF 解析、MediaMTX 流路径维护。 */
public interface
CameraService {

    /** 未填写 RTSP 时通过 ONVIF 获取；入库后在 MediaMTX 创建对应流路径。 */
    CameraVO addCamera(CameraRequest req);

    List<CameraVO> listCameras();

    CameraVO getCamera(Long id);

    /** 连接参数或 RTSP 变化时重新走 ONVIF + MediaMTX，并清除旧的诊断/兼容性结论。 */
    CameraVO updateCamera(Long id, CameraRequest req);

    /** 同时删除主路径和兼容输出路径，避免 MediaMTX 残留空壳。 */
    void deleteCamera(Long id);

    /** 兼容旧接口：仅按当前 MediaMTX 状态返回，不再触发重建。 */
    CameraVO refreshStream(Long id);

    /** 强制重建 MediaMTX 路径，用于修复僵尸会话或旧配置。 */
    CameraVO reconnectStream(Long id);

    /** 准备播放：必要时启动兼容输出并在超时预算内等待路径就绪，返回真实就绪状态。 */
    CameraVO preparePlayback(Long id);
}
