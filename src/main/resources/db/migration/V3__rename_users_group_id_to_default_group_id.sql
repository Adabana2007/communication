-- ------------------------------------------------------------
-- 步骤 1：只有 group_id 时改名；已有 default_group_id 时空操作
-- ------------------------------------------------------------
SET @has_new := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE()
                    AND table_name = 'users'
                    AND column_name = 'default_group_id');

SET @sql := IF(@has_new = 0,
  'ALTER TABLE `users` CHANGE COLUMN `group_id` `default_group_id` BIGINT NULL DEFAULT 1 COMMENT ''默认展示群ID，对应 group_info.group_id（1 = 未分类组）''',
  'DO 0');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 步骤 2：改名后仍然没有该列 → 说明 group_id 也不存在（异常库），补一列
-- ------------------------------------------------------------
SET @has_new := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE()
                    AND table_name = 'users'
                    AND column_name = 'default_group_id');

SET @sql := IF(@has_new = 0,
  'ALTER TABLE `users` ADD COLUMN `default_group_id` BIGINT NULL DEFAULT 1 COMMENT ''默认展示群ID，对应 group_info.group_id（1 = 未分类组）'' AFTER `role`',
  'DO 0');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
