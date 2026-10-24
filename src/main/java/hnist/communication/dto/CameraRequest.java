package hnist.communication.dto;

import lombok.Data;

@Data
public class CameraRequest {
    private String name;

    /** 设备编号（内部编码） */
    private String deviceCode;
    private String deviceModel;

    private String ip;

    /** ONVIF端口，默认80 */
    private Integer port;

    private String username;

    private String password;

    /** 品牌: hikvision / dahua / generic */
    private String brand;

    /** 手动填写的RTSP地址，ONVIF失败时兜底 */
    private String rtspUrl;
    /** RTSP传输协议：automatic/tcp/udp，默认自动协商 */
    private String sourceProtocol;
    /** direct / existing / compatible；空路径使用原始 cam_<id>。 */
    private String playbackMode;
    private String playbackPath;
    /** 截图地址（用于缩略图） */
    private String snapshotUrl;
}
