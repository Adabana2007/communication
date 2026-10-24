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
@TableName("map_downloads")
public class MapDownload {
    /** 下载中 */
    public static final String STATUS_DOWNLOADING = "downloading";
    /** 已完成 */
    public static final String STATUS_COMPLETED = "completed";
    /** 失败 */
    public static final String STATUS_FAILED = "failed";
    /** 未下载 */
    public static final String STATUS_NONE = "none";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 城市ID */
    private Long regionId;
    /** downloading / completed / failed */
    private String status;
    /** 进度 0-100 */
    private Integer progress;
    /** 实际下载字节数 */
    private Long fileSize;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
