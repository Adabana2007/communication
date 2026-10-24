package hnist.communication.vo;

import hnist.communication.entity.PhoneCamera;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneCameraVO {
    private Long id;
    private Long userId;
    /** 关联用户名 */
    private String username;
    private String deviceName;
    private String deviceId;
    /** 0=离线 1=在线 2=推流中 */
    private Integer status;
    /** HLS播放地址（兼容性兜底） */
    private String hlsUrl;
    /** WebRTC播放地址（MediaMTX分配，WHEP拉流） */
    private String webrtcUrl;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;

    public static PhoneCameraVO from(PhoneCamera entity) {
        return PhoneCameraVO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .deviceName(entity.getDeviceName())
                .deviceId(entity.getDeviceId())
                .status(entity.getStatus())
                .hlsUrl(entity.getHlsUrl())
                .lastHeartbeat(entity.getLastHeartbeat())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public static PhoneCameraVO from(PhoneCamera entity, String username) {
        PhoneCameraVO vo = from(entity);
        vo.setUsername(username);
        return vo;
    }

    /**
     * 使用外部计算后的有效状态构建VO（用于在线状态与通讯录人员同步等展示层修正，
     * 不改动实体本身的 status 字段）
     */
    public static PhoneCameraVO from(PhoneCamera entity, String username, Integer effectiveStatus) {
        PhoneCameraVO vo = from(entity);
        vo.setUsername(username);
        vo.setStatus(effectiveStatus);
        return vo;
    }
}
