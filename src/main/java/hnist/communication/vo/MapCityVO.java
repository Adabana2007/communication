package hnist.communication.vo;

import hnist.communication.entity.MapDownload;
import hnist.communication.entity.MapRegion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MapCityVO {
    private Long id;
    private String name;
    /** 行政区划代码 */
    private String adcode;
    /** 大小文本（已导入时为真实包大小，否则为预估） */
    private String size;
    /** 已入库瓦片张数（0=未导入） */
    private Long tileCount;
    /** 地图包版本 */
    private Integer mapVersion;
    /** none / downloading / completed / failed */
    private String status;
    /** 进度 0-100 */
    private Integer progress;
    private Long downloadId;

    public static MapCityVO from(MapRegion region, String status, Integer progress, Long downloadId) {
        boolean hasTiles = region.getTileCount() != null && region.getTileCount() > 0;
        String sizeText = hasTiles && region.getPackageSize() != null && region.getPackageSize() > 0
                ? humanSize(region.getPackageSize())
                : (region.getSizeText() != null ? region.getSizeText() : "-");
        return MapCityVO.builder()
                .id(region.getId()).name(region.getName())
                .adcode(region.getAdcode()).size(sizeText)
                .tileCount(region.getTileCount() != null ? region.getTileCount() : 0L)
                .mapVersion(region.getMapVersion() != null ? region.getMapVersion() : 0)
                .status(status != null ? status : MapDownload.STATUS_NONE)
                .progress(progress != null ? progress : 0)
                .downloadId(downloadId).build();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024);
        }
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024);
        }
        if (bytes >= 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
