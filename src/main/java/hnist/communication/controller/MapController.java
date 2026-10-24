package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.dto.MapDownloadUpdateRequest;
import hnist.communication.service.MapService;
import hnist.communication.vo.MapCityVO;
import hnist.communication.vo.MapProvinceVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;

/** 离线地图省份/城市浏览与用户下载记录维护。 */
@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
public class MapController {

    private final MapService mapService;

    /** 省列表（含每省已下载城市数） */
    @GetMapping("/provinces")
    public Result<List<MapProvinceVO>> listProvinces(Authentication authentication) {
        return Result.success(mapService.listProvinces((Long) authentication.getPrincipal()));
    }

    /** 某省下市列表（含下载状态） */
    @GetMapping("/provinces/{provinceId}/cities")
    public Result<List<MapCityVO>> listCities(@PathVariable Long provinceId,
                                              Authentication authentication) {
        return Result.success(mapService.listCities(provinceId, (Long) authentication.getPrincipal()));
    }

    @PostMapping("/cities/{regionId}/download")
    public Result<Long> startDownload(@PathVariable Long regionId,
                                       Authentication authentication) {
        return Result.success(mapService.startDownload((Long) authentication.getPrincipal(), regionId));
    }

    /** 更新下载状态/进度（含真实字节数） */
    @PutMapping("/downloads/{id}")
    public Result<Void> updateDownload(@PathVariable Long id,
                                        @Valid @RequestBody MapDownloadUpdateRequest req,
                                        Authentication authentication) {
        mapService.updateDownload(id, (Long) authentication.getPrincipal(), req.getStatus(), req.getProgress(),
                req.getFileSize(), req.getErrorMsg());
        return Result.success();
    }

    @DeleteMapping("/downloads/{id}")
    public Result<Void> deleteDownload(@PathVariable Long id,
                                        Authentication authentication) {
        mapService.deleteDownload(id, (Long) authentication.getPrincipal());
        return Result.success();
    }
}
