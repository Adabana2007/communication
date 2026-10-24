-- 报警媒体绑定上传者，避免调用方把他人上传的媒体挂到自己的报警记录上。
-- 拆分为独立迁移，避免已执行 V7 的环境出现 Flyway checksum 不一致。
SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'alarm_media' AND column_name = 'uploader_id');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `alarm_media` ADD COLUMN `uploader_id` BIGINT DEFAULT NULL COMMENT ''上传者用户ID，限制只能绑定本人媒体'' AFTER `id`',
  'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
