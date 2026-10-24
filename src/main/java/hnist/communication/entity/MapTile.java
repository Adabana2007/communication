package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 离线地图瓦片表（一张瓦片 = 一行，BLOB 存图片数据）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("map_tiles")
public class MapTile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属地区（map_regions.id） */
    private Long regionId;

    /** 缩放级别 */
    private Integer z;

    /** 列号 */
    private Integer x;

    /** 行号（XYZ 标准，从上往下） */
    private Integer y;

    /** 瓦片图片数据（PNG 栅格瓦片） */
    private byte[] tileData;

    /** 字节数（统计用） */
    private Integer size;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
