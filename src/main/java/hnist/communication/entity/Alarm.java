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
@TableName("alarms")
public class Alarm {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String description;
    private Double latitude;
    private Double longitude;
    private String imagePaths;
    private String voicePath;
    /** 语音时长（秒） */
    private Integer voiceDuration;
    /** 处理状态：pending=未处理 processing=处理中 done=已处理 */
    private String status;
    private String handleRemark;
    /** 处理人用户ID */
    private Long handledBy;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;

    /** 媒体关联ID（非数据库列，随请求体携带） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<Long> imageMediaIds;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long voiceMediaId;
}
