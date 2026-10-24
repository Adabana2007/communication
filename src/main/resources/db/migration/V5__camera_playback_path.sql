-- 空值沿用原 cam_<id>；可复用运维已经在 MediaMTX 中配置的兼容输出。
ALTER TABLE cameras ADD COLUMN playback_path VARCHAR(160) NULL;
-- direct=原始路径，existing=现有输出，compatible=显式启用的按需兼容输出。
ALTER TABLE cameras ADD COLUMN playback_mode VARCHAR(20) NOT NULL DEFAULT 'direct';
-- 仅管理员在有诊断证据时选择 compatible 后写入；源/凭据/传输改变后不再匹配。
ALTER TABLE cameras ADD COLUMN compatibility_source_hash VARCHAR(64) NULL;
