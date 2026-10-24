package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.MapDownload;
import hnist.communication.entity.MapRegion;
import hnist.communication.mapper.MapDownloadMapper;
import hnist.communication.mapper.MapRegionMapper;
import hnist.communication.service.MapService;
import hnist.communication.vo.MapCityVO;
import hnist.communication.vo.MapProvinceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 离线地图省市目录与用户下载记录管理。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapServiceImpl implements MapService {

    private final MapRegionMapper regionMapper;
    private final MapDownloadMapper downloadMapper;

    @Override
    public List<MapProvinceVO> listProvinces(Long userId) {
        List<MapRegion> provinces = regionMapper.selectList(
                new LambdaQueryWrapper<MapRegion>().eq(MapRegion::getLevel, 1)
                        .orderByAsc(MapRegion::getSortOrder));

        // 批量查市后按 parent_id 分组；种子 SQL 幂等缺失会产生同名重复行，按 name 去重保留首条。
        Map<Long, List<MapRegion>> citiesByProvince = regionMapper.selectList(
                new LambdaQueryWrapper<MapRegion>().eq(MapRegion::getLevel, 2))
                .stream()
                .collect(Collectors.groupingBy(MapRegion::getParentId))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> deduplicateByName(e.getValue()), (left, right) -> left));

        // 省份进度只统计已完成下载（STATUS_COMPLETED），下载中的记录不计入。
        Map<Long, MapDownload> downloads = downloadMapper.selectList(
                new LambdaQueryWrapper<MapDownload>()
                        .eq(MapDownload::getUserId, userId)
                        .eq(MapDownload::getStatus, MapDownload.STATUS_COMPLETED))
                .stream().collect(Collectors.toMap(MapDownload::getRegionId, d -> d, (a, b) -> a));

        List<MapProvinceVO> result = new ArrayList<>();
        for (MapRegion province : provinces) {
            List<MapRegion> cities = citiesByProvince.getOrDefault(province.getId(), List.of());
            long downloaded = cities.stream().filter(c -> downloads.containsKey(c.getId())).count();
            result.add(MapProvinceVO.builder()
                    .id(province.getId()).name(province.getName())
                    .totalCount(cities.size()).downloadedCount((int) downloaded).build());
        }
        return result;
    }

    @Override
    public List<MapCityVO> listCities(Long provinceId, Long userId) {
        List<MapRegion> rawCities = regionMapper.selectList(
                new LambdaQueryWrapper<MapRegion>()
                        .eq(MapRegion::getLevel, 2)
                        .eq(MapRegion::getParentId, provinceId)
                        .orderByAsc(MapRegion::getSortOrder));
        List<MapRegion> cities = deduplicateByName(rawCities);

        // 城市列表需要展示下载中/失败等所有状态，因此这里不按 STATUS_COMPLETED 过滤。
        Map<Long, MapDownload> downloads = downloadMapper.selectList(
                new LambdaQueryWrapper<MapDownload>()
                        .eq(MapDownload::getUserId, userId))
                .stream().collect(Collectors.toMap(MapDownload::getRegionId, d -> d, (a, b) -> a));

        return cities.stream().map(city -> {
            MapDownload d = downloads.get(city.getId());
            return MapCityVO.from(city,
                    d != null ? d.getStatus() : null,
                    d != null ? d.getProgress() : null,
                    d != null ? d.getId() : null);
        }).toList();
    }

    /** 相同 name 只保留首条：种子 SQL 幂等缺失时防御前端重复显示。 */
    private List<MapRegion> deduplicateByName(List<MapRegion> list) {
        LinkedHashMap<String, MapRegion> seen = new LinkedHashMap<>();
        for (MapRegion r : list) {
            seen.putIfAbsent(r.getName(), r);
        }
        return new ArrayList<>(seen.values());
    }

    @Override
    @Transactional
    public Long startDownload(Long userId, Long regionId) {
        MapRegion city = regionMapper.selectById(regionId);
        if (city == null) {
            throw new BusinessException("地区不存在");
        }
        // 仅市级（level 2）提供离线包，省级不再开放下载。
        if (city.getLevel() != 2) {
            throw new BusinessException("该地区不支持下载");
        }
        // tileCount 为 0 说明尚未导入瓦片，下载只会得到空包。
        if (city.getTileCount() == null || city.getTileCount() == 0) {
            throw new BusinessException("该地区尚未导入地图数据，暂不可下载");
        }

        // 同一用户同一地区复用已有记录并重置进度，避免重复插入下载记录。
        MapDownload existing = downloadMapper.selectOne(
                new LambdaQueryWrapper<MapDownload>()
                        .eq(MapDownload::getUserId, userId)
                        .eq(MapDownload::getRegionId, regionId));

        if (existing != null) {
            existing.setStatus(MapDownload.STATUS_DOWNLOADING);
            existing.setProgress(0);
            existing.setFileSize(null);
            existing.setErrorMsg(null);
            downloadMapper.updateById(existing);
            return existing.getId();
        }

        MapDownload deleted = downloadMapper.selectByUserAndRegionIncludingDeleted(userId, regionId);
        if (deleted != null) {
            deleted.setStatus(MapDownload.STATUS_DOWNLOADING);
            deleted.setProgress(0);
            deleted.setFileSize(null);
            deleted.setErrorMsg(null);
            downloadMapper.restoreDeleted(deleted);
            return deleted.getId();
        }

        MapDownload download = MapDownload.builder()
                .userId(userId).regionId(regionId).status(MapDownload.STATUS_DOWNLOADING).progress(0).build();
        downloadMapper.insert(download);
        return download.getId();
    }

    @Override
    @Transactional
    public void updateDownload(Long downloadId, Long userId, String status, Integer progress,
                               Long fileSize, String errorMsg) {
        MapDownload download = downloadMapper.selectById(downloadId);
        // 按 userId 校验归属：越权与记录不存在统一返回同一提示，避免暴露他人记录是否存在。
        if (download == null || !download.getUserId().equals(userId)) {
            throw new BusinessException("下载记录不存在");
        }
        if (status != null) download.setStatus(status);
        if (progress != null) download.setProgress(progress);
        if (fileSize != null) download.setFileSize(fileSize);
        if (errorMsg != null) download.setErrorMsg(errorMsg);
        downloadMapper.updateById(download);
    }

    @Override
    @Transactional
    public void deleteDownload(Long downloadId, Long userId) {
        MapDownload download = downloadMapper.selectById(downloadId);
        // 删除同样校验归属，防止凭 downloadId 删除他人下载记录。
        if (download == null || !download.getUserId().equals(userId)) {
            throw new BusinessException("下载记录不存在");
        }
        downloadMapper.deleteById(downloadId);
    }
}
