package hnist.communication.vo;

import hnist.communication.entity.Camera;
import hnist.communication.util.SensitiveDataMasker;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 摄像头视图对象（不含密码）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraVO {
    private Long id;
    private String name;
    private String deviceCode;
    private String deviceModel;
    private String ip;
    private Integer port;
    private String username;
    /** 品牌: hikvision / dahua / generic */
    private String brand;
    /** RTSP流地址 */
    private String rtspUrl;
    private String sourceProtocol;
    private String playbackMode;
    private String playbackPath;
    /** ready / not_ready / unknown / unconfigured；表示媒体源，不表示当前客户端已出画。 */
    private String sourceState;
    private String sourceMessage;
    private String playbackSourceState;
    private java.time.Instant checkedAt;
    private java.util.List<String> tracks;
    private hnist.communication.manager.camera.CameraDiagnostics.Report diagnostic;
    private String profileToken;
    private String videoCodec;
    private String audioCodec;
    private String lastError;
    /** 截图地址（用于缩略图） */
    private String snapshotUrl;
    /** WebRTC播放地址 */
    private String webrtcUrl;
    /** HLS播放地址（兼容性兜底） */
    private String hlsUrl;
    private String mediamtxPath;
    /** 0=离线 1=在线 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CameraVO from(Camera camera) {
        return CameraVO.builder()
                .id(camera.getId()).name(camera.getName())
                .deviceCode(camera.getDeviceCode()).deviceModel(camera.getDeviceModel())
                .ip(camera.getIp()).port(camera.getPort())
                .username(camera.getUsername()).brand(camera.getBrand())
                .rtspUrl(camera.getRtspUrl()).sourceProtocol(camera.getSourceProtocol())
                .playbackMode(camera.getPlaybackMode()).playbackPath(camera.getPlaybackPath())
                .profileToken(camera.getProfileToken())
                .videoCodec(camera.getVideoCodec()).audioCodec(camera.getAudioCodec())
                .lastError(camera.getLastError()).snapshotUrl(camera.getSnapshotUrl())
                .webrtcUrl(camera.getWebrtcUrl()).hlsUrl(camera.getHlsUrl())
                .mediamtxPath(camera.getMediamtxPath()).status(camera.getStatus())
                .createdAt(camera.getCreatedAt()).updatedAt(camera.getUpdatedAt())
                .build();
    }

    /** 非管理员视图隐藏摄像头账号、RTSP 内嵌凭据和可能带鉴权参数的截图地址。 */
    public void maskCredentials() {
        username = SensitiveDataMasker.maskSecret(username);
        rtspUrl = SensitiveDataMasker.maskUrlCredentials(rtspUrl);
        snapshotUrl = SensitiveDataMasker.maskUrlCredentials(snapshotUrl);
    }
}
