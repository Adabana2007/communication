package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 报警图片/语音媒体：二进制存 LONGBLOB，与报警记录同库以保证上报事务内一致性。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("alarm_media")
public class AlarmMedia {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 上传者，用于阻止用户抢占他人尚未绑定的媒体。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long uploaderId;
    /** 关联报警ID；上传后未上报为 NULL，上报事务内关联 */
    private Long alarmId;
    /** 类型: image/voice */
    private String mediaType;
    private String mime;
    /** 原始文件名 */
    private String fileName;
    /** 媒体二进制内容 */
    private byte[] data;
    /** 字节数 */
    private Long size;
    private LocalDateTime createdAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
