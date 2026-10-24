package hnist.communication.service;

import hnist.communication.vo.MapCityVO;
import hnist.communication.vo.MapProvinceVO;

import java.util.List;

/** 离线地图省市目录与用户下载记录管理。 */
public interface MapService {
    /** 省份列表只统计已完成的下载。 */
    List<MapProvinceVO> listProvinces(Long userId);
    /** 城市列表返回所有状态的下载记录（下载中/失败/完成）。 */
    List<MapCityVO> listCities(Long provinceId, Long userId);
    /** 仅市级且已导入瓦片的地区可下载；重复调用复用同一记录并重置进度。 */
    Long startDownload(Long userId, Long regionId);
    /** fileSize 为实际下载字节数；按 userId 校验下载记录归属。 */
    void updateDownload(Long downloadId, Long userId, String status, Integer progress, Long fileSize, String errorMsg);
    /** 按 userId 校验归属后删除。 */
    void deleteDownload(Long downloadId, Long userId);
}
