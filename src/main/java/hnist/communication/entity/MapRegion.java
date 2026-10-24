package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 地图地区表（省+市统一，parent_id=0 表示省）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("map_regions")
public class MapRegion {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 0=省, 非0=所属省ID */
    private Long parentId;
    private String name;
    /** 百度地图城市码（历史字段，离线地图改用 adcode） */
    private String baiduCode;
    /** 行政区划代码（如 430600=岳阳市） */
    private String adcode;
    /** 覆盖范围：西,南,东,北（经纬度） */
    private String bbox;
    /** 地图包最低/最高瓦片层级及统一初始层级 */
    private Integer minZoom;
    private Integer maxZoom;
    private Integer defaultZoom;
    /** 地区默认中心点 */
    private Double centerLng;
    private Double centerLat;
    /** 地图包版本（每次导入 +1） */
    private Integer mapVersion;
    /** 已入库瓦片张数 */
    private Long tileCount;
    /** 地图包大小（字节） */
    private Long packageSize;
    private String packageMd5;
    /** 实际 ZIP 文件 SHA-256（客户端完整性校验） */
    private String packageSha256;
    /** 1=省 2=市 */
    private Integer level;
    /** 大小文本（未导入时为预估，导入后为真实） */
    private String sizeText;
    private Integer sortOrder;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
