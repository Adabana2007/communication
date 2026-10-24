package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.MapRegion;
import hnist.communication.entity.MapTile;
import hnist.communication.mapper.MapRegionMapper;
import hnist.communication.mapper.MapTileMapper;
import hnist.communication.service.MapTileService;
import hnist.communication.cache.TileCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 离线地图瓦片存取：MySQL 存储、内存 LRU 缓存，并按需构建 zip 地图包。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MapTileServiceImpl implements MapTileService {

    /** 瓦片内存缓存上限：256MB */
    private static final long CACHE_MAX_BYTES = 256L * 1024 * 1024;
    /** 批量入库每批行数 */
    private static final int BATCH_SIZE = 500;
    /** 打包游标每批行数 */
    private static final int PACK_BATCH = 1000;
    /** 单个瓦片解压后的硬上限，防止 gzip/zip bomb 占满堆内存。 */
    private static final int MAX_TILE_BYTES = 16 * 1024 * 1024;
    /** 地图包解压后的总字节上限与条目数上限。 */
    private static final long MAX_PACKAGE_UNCOMPRESSED_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_PACKAGE_ENTRIES = 3_000_000;

    private final MapTileMapper tileMapper;
    private final MapRegionMapper regionMapper;
    private final JdbcTemplate jdbcTemplate;

    private final TileCache tileCache = new TileCache(CACHE_MAX_BYTES);
    /** 地区 -> 已构建的地图包（按版本失效） */
    private final Map<Long, PackageInfo> packageCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------
    // 瓦片查询
    // ------------------------------------------------------------

    @Override
    public byte[] getTile(Long regionId, int z, int x, int y) {
        String key = regionId + "_" + z + "_" + x + "_" + y;
        byte[] data = tileCache.get(key);
        if (data != null) {
            return data;
        }
        MapTile tile = tileMapper.selectOne(new LambdaQueryWrapper<MapTile>()
                .eq(MapTile::getRegionId, regionId)
                .eq(MapTile::getZ, z)
                .eq(MapTile::getX, x)
                .eq(MapTile::getY, y)
                .select(MapTile::getTileData));
        if (tile == null) {
            return null;
        }
        data = tile.getTileData();
        tileCache.put(key, data);
        return data;
    }

    // ------------------------------------------------------------
    // 导入地图包
    // ------------------------------------------------------------

    @Override
    @Transactional
    public Map<String, Object> importPackage(Long regionId, MultipartFile file) {
        MapRegion region = regionMapper.selectById(regionId);
        // 仅市级（level 2）可导入离线包，与 MapService.startDownload 的限制保持一致。
        if (region == null) {
            throw new BusinessException("地区不存在");
        }
        if (region.getLevel() != 2) {
            throw new BusinessException("该地区不支持导入");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传非空的地图包");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".zip")) {
            throw new BusinessException("请上传 zip 格式的地图包");
        }

        // 1) 清空旧瓦片（重新导入 = 整体替换）
        jdbcTemplate.update("UPDATE map_tiles SET is_deleted = 1 WHERE region_id = ? AND is_deleted = 0", regionId);
        tileCache.clear();

        // 2) 流式解析并攒批入库，避免整个地图包一次性读入内存
        long tileCount = 0;
        long totalBytes = 0;
        int minZoom = Integer.MAX_VALUE;
        int maxZoom = Integer.MIN_VALUE;
        int entryCount = 0;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        try (ZipInputStream zin = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (++entryCount > MAX_PACKAGE_ENTRIES) {
                    throw new BusinessException("地图包条目数超过安全上限");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                int[] zxy = parseTilePath(entry.getName());
                if (zxy == null) {
                    continue;
                }
                byte[] data = readEntry(zin, entry);
                // 关键：planetiler 产出的瓦片是 gzip 压缩的 pbf，浏览器/离线 file://
                // 无法自动解压，这里统一解压成裸 pbf 再入库
                data = ensureGunzip(data);
                if (data.length > MAX_TILE_BYTES) {
                    throw new BusinessException("单个瓦片超过解压上限: " + entry.getName());
                }
                totalBytes += data.length;
                if (totalBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                    throw new BusinessException("地图包解压总量超过安全上限");
                }
                batch.add(new Object[]{regionId, zxy[0], zxy[1], zxy[2], data, data.length});
                tileCount++;
                minZoom = Math.min(minZoom, zxy[0]);
                maxZoom = Math.max(maxZoom, zxy[0]);
                if (batch.size() >= BATCH_SIZE) {
                    flushBatch(batch);
                }
            }
            flushBatch(batch);
        } catch (IOException e) {
            throw new BusinessException("地图包解析失败：" + e.getMessage());
        }

        if (tileCount == 0) {
            throw new BusinessException("地图包内未找到瓦片文件（zip 内应为 z/x/y.pbf 或 z/x/y.png 结构）");
        }

        // 3) 更新地区元数据：版本 +1 用于让客户端和打包缓存识别新包。
        region.setTileCount(tileCount);
        region.setPackageSize(totalBytes);
        region.setMinZoom(minZoom);
        region.setMaxZoom(maxZoom);
        if (region.getDefaultZoom() == null || region.getDefaultZoom() < minZoom || region.getDefaultZoom() > maxZoom) {
            region.setDefaultZoom(Math.min(maxZoom, Math.max(minZoom, 14)));
        }
        applyCenterFromBbox(region);
        region.setMapVersion(region.getMapVersion() == null ? 1 : region.getMapVersion() + 1);
        region.setPackageMd5(null); // 打包下载时再计算真实 MD5
        region.setPackageSha256(null);
        region.setSizeText(humanSize(totalBytes));
        regionMapper.updateById(region);

        // 4) 失效旧的打包缓存
        PackageInfo old = packageCache.remove(regionId);
        deletePackageFile(old);

        return Map.of("tileCount", tileCount, "totalBytes", totalBytes);
    }

    // ------------------------------------------------------------
    // 打包下载
    // ------------------------------------------------------------

    @Override
    public synchronized PackageInfo getPackage(Long regionId) {
        // 方法级同步：避免并发请求同时写同一个临时 zip，读到写了一半的包。
        MapRegion region = regionMapper.selectById(regionId);
        if (region == null) {
            throw new BusinessException("地区不存在");
        }
        if (region.getTileCount() == null || region.getTileCount() == 0) {
            throw new BusinessException("该地区尚未导入地图数据");
        }
        int version = region.getMapVersion() == null ? 0 : region.getMapVersion();
        // 缓存按 mapVersion 失效：重新导入或删除瓦片会递增版本号。
        PackageInfo cached = packageCache.get(regionId);
        if (cached != null && cached.version() == version) {
            return cached;
        }
        PackageInfo built = buildPackage(region);
        packageCache.put(regionId, built);
        return built;
    }

    private PackageInfo buildPackage(MapRegion region) {
        long regionId = region.getId();
        int version = region.getMapVersion() == null ? 0 : region.getMapVersion();
        File dir = new File(System.getProperty("java.io.tmpdir"), "map-packages");
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            throw new BusinessException("地图包临时目录创建失败", e);
        }
        File zipFile = new File(dir, "map-region-" + regionId + "-v" + version + ".zip");

        // 包文件名包含地图版本。服务重启后优先复用已经完整生成且摘要已落库的包，
        // 避免首个手机下载时再次扫描全部瓦片、长时间无响应而被系统判定超时。
        if (zipFile.isFile()
                && region.getPackageSize() != null
                && region.getPackageSize() == zipFile.length()
                && region.getPackageMd5() != null && !region.getPackageMd5().isBlank()
                && region.getPackageSha256() != null && !region.getPackageSha256().isBlank()) {
            return new PackageInfo(zipFile, zipFile.length(), region.getPackageMd5(), version);
        }

        long totalBytes = 0;
        long lastId = 0;
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(zipFile))) {
            while (true) {
                // 游标分页，避免一次性把全表读进内存
                List<MapTile> rows = jdbcTemplate.query(
                        "SELECT id, z, x, y, tile_data FROM map_tiles "
                                + "WHERE region_id = ? AND is_deleted = 0 AND id > ? ORDER BY id LIMIT ?",
                        (rs, i) -> MapTile.builder()
                                .id(rs.getLong(1))
                                .z(rs.getInt(2))
                                .x(rs.getInt(3))
                                .y(rs.getInt(4))
                                .tileData(rs.getBytes(5))
                                .build(),
                        regionId, lastId, PACK_BATCH);
                if (rows.isEmpty()) {
                    break;
                }
                for (MapTile t : rows) {
                    // 库里的瓦片可能是 gzip 压缩（planetiler 默认产出），
                    // 离线渲染拿到的原始字节流无法解压，打包时统一转成裸数据。
                    byte[] data = ensurePlainPbf(t.getTileData());
                    // 扩展名按瓦片实际格式：PNG 魔数用 .png（栅格），否则用 .pbf（矢量）。
                    String ext = isPng(data) ? ".png" : ".pbf";
                    ZipEntry entry = new ZipEntry(t.getZ() + "/" + t.getX() + "/" + t.getY() + ext);
                    zout.putNextEntry(entry);
                    zout.write(data);
                    zout.closeEntry();
                    totalBytes += data.length;
                    lastId = t.getId();
                }
            }
            String manifest = buildManifest(region);
            zout.putNextEntry(new ZipEntry("manifest.json"));
            zout.write(manifest.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
            zout.finish();
        } catch (IOException e) {
            throw new BusinessException("地图包构建失败：" + e.getMessage());
        }

        // 必须在 ZipOutputStream 完全关闭后计算摘要；Windows 下边写边读会得到
        // 未写完的中央目录，导致客户端校验失败或偶发“解压卡住”。
        try {
            String md5Hex = digestFile(zipFile, "MD5");
            String sha256Hex = digestFile(zipFile, "SHA-256");
            long packageSize = zipFile.length();

            region.setPackageSize(packageSize);
            region.setPackageMd5(md5Hex);
            region.setPackageSha256(sha256Hex);
            region.setSizeText(humanSize(packageSize));
            regionMapper.updateById(region);

            return new PackageInfo(zipFile, packageSize, md5Hex, version);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BusinessException("地图包摘要计算失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------
    // 元数据
    // ------------------------------------------------------------

    /** 若瓦片为 gzip 压缩（文件头 1F 8B），解压为裸 pbf；否则原样返回 */
    private byte[] ensurePlainPbf(byte[] data) {
        if (data == null || data.length < 2) {
            return data;
        }
        if ((data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
                return readBounded(gz, "地图瓦片gzip解压后数据");
            } catch (IOException e) {
                throw new BusinessException("地图瓦片gzip解压失败", e);
            }
        }
        return data;
    }

    @Override
    public Map<String, Object> getMeta(Long regionId) {
        MapRegion region = regionMapper.selectById(regionId);
        if (region == null) {
            throw new BusinessException("地区不存在");
        }
        return metaOf(region);
    }

    @Override
    public Map<String, Object> getActiveRegionMeta() {
        // 只认市级：省级下载/导入已关闭，取排序最前的已导入城市作为默认地区。
        MapRegion region = regionMapper.selectOne(new LambdaQueryWrapper<MapRegion>()
                .eq(MapRegion::getLevel, 2)
                .gt(MapRegion::getTileCount, 0)
                .orderByAsc(MapRegion::getSortOrder)
                .last("LIMIT 1"));
        return region == null ? Map.of("ready", false) : metaOf(region);
    }

    private Map<String, Object> metaOf(MapRegion region) {
        applyCenterFromBbox(region);
        long tileCount = region.getTileCount() == null ? 0 : region.getTileCount();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", region.getId());
        meta.put("name", region.getName());
        meta.put("adcode", region.getAdcode() == null ? "" : region.getAdcode());
        meta.put("bbox", region.getBbox() == null ? "" : region.getBbox());
        meta.put("minZoom", region.getMinZoom() == null ? 0 : region.getMinZoom());
        meta.put("maxZoom", region.getMaxZoom() == null ? 18 : region.getMaxZoom());
        meta.put("defaultZoom", region.getDefaultZoom() == null ? 14 : region.getDefaultZoom());
        meta.put("centerLng", region.getCenterLng() == null ? 0D : region.getCenterLng());
        meta.put("centerLat", region.getCenterLat() == null ? 0D : region.getCenterLat());
        meta.put("version", region.getMapVersion() == null ? 0 : region.getMapVersion());
        meta.put("tileCount", tileCount);
        meta.put("packageSize", region.getPackageSize() == null ? 0 : region.getPackageSize());
        meta.put("packageMd5", region.getPackageMd5() == null ? "" : region.getPackageMd5());
        meta.put("packageSha256", region.getPackageSha256() == null ? "" : region.getPackageSha256());
        meta.put("ready", tileCount > 0);
        return meta;
    }

    private void applyCenterFromBbox(MapRegion region) {
        if (region.getCenterLng() != null && region.getCenterLat() != null) return;
        if (region.getBbox() == null || region.getBbox().isBlank()) return;
        try {
            String[] values = region.getBbox().split(",");
            if (values.length == 4) {
                double west = Double.parseDouble(values[0].trim());
                double south = Double.parseDouble(values[1].trim());
                double east = Double.parseDouble(values[2].trim());
                double north = Double.parseDouble(values[3].trim());
                region.setCenterLng((west + east) / 2D);
                region.setCenterLat((south + north) / 2D);
            }
        } catch (NumberFormatException e) {
            log.warn("地区 {} bbox 格式无效: {}", region.getId(), region.getBbox());
        }
    }

    private String buildManifest(MapRegion region) {
        applyCenterFromBbox(region);
        return "{" +
                "\"regionId\":" + region.getId() + "," +
                "\"name\":\"" + escapeJson(region.getName()) + "\"," +
                "\"version\":" + (region.getMapVersion() == null ? 0 : region.getMapVersion()) + "," +
                "\"tileCount\":" + (region.getTileCount() == null ? 0 : region.getTileCount()) + "," +
                "\"minZoom\":" + (region.getMinZoom() == null ? 0 : region.getMinZoom()) + "," +
                "\"maxZoom\":" + (region.getMaxZoom() == null ? 18 : region.getMaxZoom()) + "," +
                "\"defaultZoom\":" + (region.getDefaultZoom() == null ? 14 : region.getDefaultZoom()) + "," +
                "\"centerLng\":" + (region.getCenterLng() == null ? 0 : region.getCenterLng()) + "," +
                "\"centerLat\":" + (region.getCenterLat() == null ? 0 : region.getCenterLat()) + "," +
                "\"bbox\":\"" + escapeJson(region.getBbox()) + "\"}";
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String digestFile(File file, String algorithm) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    // ------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------

    @Override
    @Transactional
    public void deleteTiles(Long regionId) {
        MapRegion region = regionMapper.selectById(regionId);
        if (region == null) {
            throw new BusinessException("地区不存在");
        }
        jdbcTemplate.update("UPDATE map_tiles SET is_deleted = 1 WHERE region_id = ? AND is_deleted = 0", regionId);
        tileCache.clear();
        // 显式 set 更新：updateById 默认跳过 null 字段，会让 packageMd5 残留旧值，
        // meta 里出现"已删除但仍显示 md5"的假数据。
        regionMapper.update(null, new LambdaUpdateWrapper<MapRegion>()
                .eq(MapRegion::getId, regionId)
                .set(MapRegion::getTileCount, 0L)
                .set(MapRegion::getPackageSize, 0L)
                .set(MapRegion::getPackageMd5, null)
                .set(MapRegion::getSizeText, "未导入")
                .set(MapRegion::getMapVersion,
                        region.getMapVersion() == null ? 1 : region.getMapVersion() + 1));

        PackageInfo old = packageCache.remove(regionId);
        deletePackageFile(old);
    }

    // ------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------

    /** 解析 zip 条目路径为 z/x/y，如 "12/3456/2345.pbf" 或 ".png"；不匹配返回 null */
    private int[] parseTilePath(String name) {
        String[] parts = name.replace('\\', '/').split("/");
        if (parts.length < 3) {
            return null;
        }
        String file = parts[parts.length - 1].toLowerCase();
        String ext;
        if (file.endsWith(".pbf")) {
            ext = ".pbf";
        } else if (file.endsWith(".png")) {
            ext = ".png";
        } else {
            return null;
        }
        try {
            int z = Integer.parseInt(parts[parts.length - 3].trim());
            int x = Integer.parseInt(parts[parts.length - 2].trim());
            int y = Integer.parseInt(file.substring(0, file.length() - ext.length()).trim());
            if (z < 0 || z > 22) {
                return null;
            }
            long side = 1L << z;
            if (x < 0 || y < 0 || x >= side || y >= side) {
                return null;
            }
            return new int[]{z, x, y};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** gzip 压缩的瓦片数据解压为裸数据（1F 8B 魔数判断） */
    private byte[] ensureGunzip(byte[] data) {
        if (data != null && data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            try (java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(data);
                 java.util.zip.GZIPInputStream gin = new java.util.zip.GZIPInputStream(bin)) {
                return readBounded(gin, "地图瓦片gzip解压后数据");
            } catch (IOException e) {
                throw new BusinessException("地图瓦片gzip解压失败", e);
            }
        }
        return data;
    }

    /** 是否为 PNG 栅格瓦片（PNG 魔数 89 50 4E 47） */
    private boolean isPng(byte[] data) {
        return data != null && data.length >= 8
                && (data[0] & 0xFF) == 0x89
                && (data[1] & 0xFF) == 0x50
                && (data[2] & 0xFF) == 0x4E
                && (data[3] & 0xFF) == 0x47;
    }

    private byte[] readEntry(InputStream in, ZipEntry entry) throws IOException {
        long declaredSize = entry.getSize();
        if (declaredSize > MAX_TILE_BYTES) {
            throw new BusinessException("单个瓦片超过解压上限: " + entry.getName());
        }
        int initial = declaredSize > 0 ? (int) Math.min(declaredSize, 1024 * 1024) : 8192;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(initial)) {
            copyBounded(in, out, "单个瓦片");
            return out.toByteArray();
        }
    }

    private byte[] readBounded(InputStream in, String label) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
            copyBounded(in, out, label);
            return out.toByteArray();
        }
    }

    private void copyBounded(InputStream in, ByteArrayOutputStream out, String label) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_TILE_BYTES) {
                throw new BusinessException(label + "超过解压上限");
            }
            out.write(buffer, 0, read);
        }
    }

    private void flushBatch(List<Object[]> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO map_tiles (region_id, z, x, y, tile_data, size, is_deleted) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0) "
                        + "ON DUPLICATE KEY UPDATE is_deleted = 0, tile_data = VALUES(tile_data), size = VALUES(size)",
                batch);
        batch.clear();
    }

    private void deletePackageFile(PackageInfo info) {
        if (info == null) {
            return;
        }
        Path path = info.file().toPath();
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("旧地图包删除失败: file={}", path.getFileName());
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String humanSize(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
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
