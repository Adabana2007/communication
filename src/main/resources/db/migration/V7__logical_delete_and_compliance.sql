-- 阿里 Java 开发手册要求业务数据使用逻辑删除。
-- 删除标记列统一为 is_deleted：0=有效，1=已删除。MyBatis-Plus 实体通过 @TableLogic 自动过滤。
-- 保留各表既有主键与时间列名，避免破坏历史数据、实体映射和现有接口契约。
-- 使用 information_schema 条件判断，兼容已经手工执行过 sql/communication.sql 的数据库。

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `users` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'friend_groups' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `friend_groups` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'contacts' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `contacts` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'group_info' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `group_info` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'group_members' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `group_members` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'cameras' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `cameras` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dict_type' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `dict_type` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dict_data' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `dict_data` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'alarms' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `alarms` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'alarm_media' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `alarm_media` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'location_history' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `location_history` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'trajectory_sessions' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `trajectory_sessions` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'call_records' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `call_records` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'devices' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `devices` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'voice_messages' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `voice_messages` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'map_regions' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `map_regions` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'map_downloads' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `map_downloads` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'map_tiles' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `map_tiles` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'user_locations' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `user_locations` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'phone_cameras' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `phone_cameras` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_messages' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `chat_messages` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'group_messages' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `group_messages` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_message_actions' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `chat_message_actions` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'audit_logs' AND column_name = 'is_deleted');
SET @ddl := IF(@has_col = 0, 'ALTER TABLE `audit_logs` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除:0=有效,1=已删除''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 高频查询/关联路径补充包含删除标记的组合索引，避免逻辑删除条件退化为全表扫描。
SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'group_members' AND index_name = 'idx_group_members_deleted_user');
SET @ddl := IF(@has_idx = 0, 'CREATE INDEX `idx_group_members_deleted_user` ON `group_members` (`is_deleted`, `user_id`)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'phone_cameras' AND index_name = 'idx_phone_cameras_deleted_user');
SET @ddl := IF(@has_idx = 0, 'CREATE INDEX `idx_phone_cameras_deleted_user` ON `phone_cameras` (`is_deleted`, `user_id`)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'map_tiles' AND index_name = 'idx_map_tiles_deleted_region');
SET @ddl := IF(@has_idx = 0, 'CREATE INDEX `idx_map_tiles_deleted_region` ON `map_tiles` (`is_deleted`, `region_id`, `z`, `x`, `y`)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'group_messages' AND index_name = 'idx_group_messages_deleted_group');
SET @ddl := IF(@has_idx = 0, 'CREATE INDEX `idx_group_messages_deleted_group` ON `group_messages` (`is_deleted`, `group_id`, `created_at`)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'location_history' AND index_name = 'idx_location_history_deleted_user');
SET @ddl := IF(@has_idx = 0, 'CREATE INDEX `idx_location_history_deleted_user` ON `location_history` (`is_deleted`, `user_id`, `recorded_at`)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
