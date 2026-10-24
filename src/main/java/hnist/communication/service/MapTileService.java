package hnist.communication.service;

import hnist.communication.entity.MapRegion;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Map;

/** 离线地图瓦片服务：瓦片查询、地图包导入、打包下载、元数据管理。 */
public interface MapTileService {

    /** 获取瓦片原始字节（pbf 或 png），不存在返回 null。 */
    byte[] getTile(Long regionId, int z, int x, int y);

    /** 导入 zip 地图包（内部为 z/x/y.pbf 或 .png），整体替换旧瓦片并返回入库统计。 */
    Map<String, Object> importPackage(Long regionId, MultipartFile file);

    /** 惰性构建并按地图版本缓存 zip 包，返回文件、大小与摘要。 */
    PackageInfo getPackage(Long regionId);

    /** 返回版本、瓦片数、包大小、MD5/SHA-256 等元数据。 */
    Map<String, Object> getMeta(Long regionId);

    /** 返回排序最前的已导入城市（市级）作为默认地区。 */
    Map<String, Object> getActiveRegionMeta();

    /** 删除全部瓦片并重置地区包元数据，版本 +1 使旧包缓存失效。 */
    void deleteTiles(Long regionId);

    /** 地图包文件信息 */
    record PackageInfo(File file, long size, String md5, int version) {
    }
}
