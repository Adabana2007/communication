-- 数据安全、通话幂等、地图/摄像头能力元数据

CREATE TABLE `audit_logs` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT DEFAULT NULL,
    `username` VARCHAR(50) DEFAULT NULL,
    `method` VARCHAR(10) NOT NULL,
    `request_path` VARCHAR(500) NOT NULL,
    `client_ip` VARCHAR(64) DEFAULT NULL,
    `response_status` INT NOT NULL,
    `success` TINYINT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_audit_user_time` (`user_id`, `created_at`),
    INDEX `idx_audit_path_time` (`request_path`(191), `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理与数据变更审计日志';

ALTER TABLE `call_records`
    ADD UNIQUE KEY `uk_call_room` (`room_id`);

ALTER TABLE `map_regions`
    ADD COLUMN `min_zoom` INT DEFAULT 0 COMMENT '地图包最低瓦片层级' AFTER `bbox`,
    ADD COLUMN `max_zoom` INT DEFAULT 18 COMMENT '地图包最高瓦片层级' AFTER `min_zoom`,
    ADD COLUMN `default_zoom` INT DEFAULT 14 COMMENT '登录后的统一初始层级' AFTER `max_zoom`,
    ADD COLUMN `center_lng` DECIMAL(10,7) DEFAULT NULL COMMENT '地区默认中心经度' AFTER `default_zoom`,
    ADD COLUMN `center_lat` DECIMAL(10,7) DEFAULT NULL COMMENT '地区默认中心纬度' AFTER `center_lng`,
    ADD COLUMN `package_sha256` VARCHAR(64) DEFAULT NULL COMMENT '实际 ZIP 文件 SHA-256' AFTER `package_md5`;

-- 兼容已经导入的地图：迁移时一次性从瓦片表计算真实层级，避免必须重新导入。
UPDATE `map_regions` r
SET r.`min_zoom` = COALESCE((SELECT MIN(t.`z`) FROM `map_tiles` t WHERE t.`region_id` = r.`id`), 0),
    r.`max_zoom` = COALESCE((SELECT MAX(t.`z`) FROM `map_tiles` t WHERE t.`region_id` = r.`id`), 18),
    r.`default_zoom` = LEAST(
        COALESCE((SELECT MAX(t2.`z`) FROM `map_tiles` t2 WHERE t2.`region_id` = r.`id`), 18), 14)
WHERE r.`tile_count` > 0;

ALTER TABLE `cameras`
    ADD COLUMN `source_protocol` VARCHAR(16) NOT NULL DEFAULT 'automatic' COMMENT 'RTSP传输 automatic/tcp/udp' AFTER `rtsp_url`,
    ADD COLUMN `profile_token` VARCHAR(100) DEFAULT NULL COMMENT 'ONVIF ProfileToken' AFTER `source_protocol`,
    ADD COLUMN `video_codec` VARCHAR(32) DEFAULT NULL COMMENT '探测到的视频编码' AFTER `profile_token`,
    ADD COLUMN `audio_codec` VARCHAR(32) DEFAULT NULL COMMENT '探测到的音频编码' AFTER `video_codec`,
    ADD COLUMN `last_error` VARCHAR(1000) DEFAULT NULL COMMENT '最近一次拉流诊断错误' AFTER `audio_codec`;
