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
@TableName("phone_cameras")
public class PhoneCamera {

    /** 离线 */
    public static final int STATUS_OFFLINE = 0;
    /** 在线 */
    public static final int STATUS_ONLINE = 1;
    /** 推流中 */
    public static final int STATUS_STREAMING = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 设备名称（手机型号） */
    private String deviceName;
    /** 设备唯一标识 */
    private String deviceId;
    /** 0=离线 1=在线 2=推流中 */
    private Integer status;
    private String hlsUrl;
    private String mediamtxPath;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
