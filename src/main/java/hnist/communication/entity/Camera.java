package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("cameras")
public class Camera {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    /** 设备编号（内部编码） */
    private String deviceCode;
    private String deviceModel;
    private String ip;
    private Integer port;
    private String username;
    private String password;
    /** 品牌: hikvision / dahua / generic */
    private String brand;
    /** RTSP流地址（ONVIF自动获取或手动填写） */
    private String rtspUrl;
    /** RTSP传输协议：automatic/tcp/udp */
    private String sourceProtocol;
    /** direct / existing / compatible；空路径使用原始 cam_<id>。 */
    private String playbackMode;
    private String playbackPath;
    /** 经诊断确认并显式启用兼容输出的源指纹，不下发客户端。 */
    private String compatibilitySourceHash;
    private String profileToken;
    private String videoCodec;
    private String audioCodec;
    private String lastError;
    /** 截图地址（用于缩略图） */
    private String snapshotUrl;
    /** WebRTC播放地址（MediaMTX分配） */
    private String webrtcUrl;
    /** HLS播放地址（兼容性兜底） */
    private String hlsUrl;
    private String mediamtxPath;
    /** 0=离线 1=在线 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
