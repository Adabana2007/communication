package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.service.MapTileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Duration;
import java.util.Map;

/** 离线地图瓦片、地图包导入下载与地区元数据。 */
@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
public class MapTileController {

    private final MapTileService mapTileService;

    /** 瓦片接口（内网实时取图，公开访问；pbf=矢量瓦片，png=栅格瓦片） */
    @GetMapping("/tiles/{regionId}/{z}/{x}/{y}.{format}")
    public ResponseEntity<byte[]> getTile(@PathVariable Long regionId,
                                          @PathVariable int z,
                                          @PathVariable int x,
                                          @PathVariable int y,
                                          @PathVariable String format) {
        byte[] data = mapTileService.getTile(regionId, z, x, y);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = "pbf".equalsIgnoreCase(format)
                ? MediaType.parseMediaType("application/vnd.mapbox-vector-tile")
                : MediaType.IMAGE_PNG;
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(data);
    }

    /** 导入地图包（管理端，需登录） */
    @PostMapping("/regions/{regionId}/import")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> importPackage(@PathVariable Long regionId,
                                                     @RequestParam("file") MultipartFile file) {
        return Result.success(mapTileService.importPackage(regionId, file));
    }

    /** 下载地图包（App 离线下载用，公开；带 Content-Length 才有真实进度） */
    @GetMapping("/regions/{regionId}/package.zip")
    public ResponseEntity<Resource> downloadPackage(@PathVariable Long regionId) {
        MapTileService.PackageInfo pkg = mapTileService.getPackage(regionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(pkg.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"map-region-" + regionId + ".zip\"")
                .body(new FileSystemResource(pkg.file()));
    }

    /** 地区地图元数据（公开） */
    @GetMapping("/regions/{regionId}/meta")
    public Result<Map<String, Object>> getMeta(@PathVariable Long regionId) {
        return Result.success(mapTileService.getMeta(regionId));
    }

    /** 当前默认地图地区（公开，App 启动时确定要渲染哪个地区） */
    @GetMapping("/regions/active")
    public Result<Map<String, Object>> getActiveRegion() {
        return Result.success(mapTileService.getActiveRegionMeta());
    }

    /** 删除地区全部瓦片（管理端，需登录） */
    @DeleteMapping("/regions/{regionId}/tiles")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteTiles(@PathVariable Long regionId) {
        mapTileService.deleteTiles(regionId);
        return Result.success();
    }
}
