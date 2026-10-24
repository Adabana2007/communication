-- ============================================================
-- communication 数据库初始化脚本
--
-- 适用环境：MySQL 8.0+
-- 脚本用途：
--   1. 创建 communication 数据库和 22 张业务基础表；Flyway 升级完成后
--      业务表总数为 24 张。
--   2. 写入未分类群、用户性别字典、全国省市级地图目录等种子数据。
--   3. 不创建默认管理员；管理员由首次启动时的
--      BOOTSTRAP_ADMIN_PASSWORD 环境变量创建。
--
-- 与 Flyway 的关系：
--   本脚本是全新环境的基线初始化脚本。应用启动后，Flyway 会继续执行
--   db/migration 下的 V2-V8 及后续迁移，补齐审计日志、聊天操作记录、
--   逻辑删除索引和媒体元数据等增量结构。存量数据库的升级只走 Flyway，
--   不要用本脚本代替迁移。
--
-- 导入方式（Windows / PowerShell 推荐使用 SOURCE）：
--   mysql --default-character-set=utf8mb4 -u root -p
--   mysql> SOURCE sql/communication.sql;
--
-- 重复执行：
--   建表使用 IF NOT EXISTS，种子数据按业务唯一键判断，不会产生重复行，
--   也不会删除、覆盖已有业务数据。
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `communication`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
USE `communication`;

-- ============================================================
-- 一、用户与群组
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS `users` (
    `is_deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `user_id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username`         VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '登录名(区分大小写)',
    `password`         VARCHAR(255) NOT NULL COMMENT '密码',
    `nickname`         VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `avatar`           VARCHAR(255) DEFAULT NULL COMMENT '头像',
    `phone`            VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `email`            VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `sex`              CHAR(1)      DEFAULT NULL COMMENT '性别: 0=男 1=女',
    `role`             TINYINT      NOT NULL DEFAULT 0 COMMENT '系统角色: 0=普通用户 1=超级管理员',
    `default_group_id` BIGINT       NOT NULL DEFAULT 1 COMMENT '默认展示群ID，对应 group_info.group_id',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 好友分组表
CREATE TABLE IF NOT EXISTS `friend_groups` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `group_id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `owner_id` BIGINT NOT NULL COMMENT '拥有者ID',
    `name`     VARCHAR(50) NOT NULL COMMENT '分组名称',
    INDEX `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友分组表';

-- 联系人表
CREATE TABLE IF NOT EXISTS `contacts` (
    `is_deleted`      TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`         BIGINT NOT NULL COMMENT '所属用户ID',
    `contact_user_id` BIGINT NOT NULL COMMENT '联系人用户ID',
    `remark`          VARCHAR(50) DEFAULT NULL COMMENT '备注名',
    `friend_group_id` BIGINT DEFAULT NULL COMMENT '所属好友分组ID',
    UNIQUE KEY `uk_user_contact` (`user_id`, `contact_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='联系人表';

-- 群组表
CREATE TABLE IF NOT EXISTS `group_info` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `group_id`   BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`       VARCHAR(100) NOT NULL COMMENT '群组名称',
    `owner_id`   BIGINT NOT NULL COMMENT '群主ID',
    `avatar`     VARCHAR(255) DEFAULT NULL COMMENT '群头像',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群组表';

-- 种子：未分类组固定为 1 号群，供 users.default_group_id 的默认值引用。
INSERT IGNORE INTO `group_info` (`group_id`, `name`, `owner_id`)
VALUES (1, '未分类组', 0);

-- 群成员表
CREATE TABLE IF NOT EXISTS `group_members` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`        BIGINT AUTO_INCREMENT PRIMARY KEY,
    `group_id`  BIGINT NOT NULL COMMENT '群ID',
    `user_id`   BIGINT NOT NULL COMMENT '用户ID',
    `role`      VARCHAR(20) NOT NULL DEFAULT 'member' COMMENT '角色: owner/admin/member',
    `nickname`  VARCHAR(50) DEFAULT NULL COMMENT '群内昵称',
    `joined_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`),
    INDEX `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群成员表';

-- ============================================================
-- 二、设备、字典与告警
-- ============================================================

-- 摄像头表
CREATE TABLE IF NOT EXISTS `cameras` (
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0     COMMENT '逻辑删除:0=有效,1=已删除',
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`          VARCHAR(100) NOT NULL               COMMENT '摄像头名称',
    `device_code`   VARCHAR(50)  DEFAULT NULL           COMMENT '设备编号',
    `device_model`  VARCHAR(100) DEFAULT NULL           COMMENT '设备型号',
    `ip`            VARCHAR(50)  NOT NULL               COMMENT 'IP地址',
    `port`          INT          NOT NULL DEFAULT 80    COMMENT 'ONVIF端口',
    `username`      VARCHAR(50)  NOT NULL               COMMENT 'ONVIF账号',
    `password`      VARCHAR(255) NOT NULL               COMMENT 'ONVIF密码',
    `brand`         VARCHAR(50)  DEFAULT NULL           COMMENT '品牌: hikvision/dahua/generic',
    `rtsp_url`      VARCHAR(500) DEFAULT NULL           COMMENT 'RTSP流地址',
    `snapshot_url`  VARCHAR(500) DEFAULT NULL           COMMENT '截图地址',
    `webrtc_url`    VARCHAR(500) DEFAULT NULL           COMMENT 'WebRTC播放地址',
    `hls_url`       VARCHAR(500) DEFAULT NULL           COMMENT 'HLS播放地址',
    `mediamtx_path` VARCHAR(100) DEFAULT NULL           COMMENT 'MediaMTX中的path名称',
    `status`        TINYINT      NOT NULL DEFAULT 0     COMMENT '0=离线 1=在线',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_ip_port` (`ip`, `port`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='摄像头表';

-- 字典类型表
CREATE TABLE IF NOT EXISTS `dict_type` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`        BIGINT AUTO_INCREMENT PRIMARY KEY,
    `dict_type` VARCHAR(100) NOT NULL COMMENT '字典类型',
    `dict_name` VARCHAR(100) NOT NULL COMMENT '字典名称',
    `status`    TINYINT DEFAULT 0 COMMENT '状态: 0=正常',
    UNIQUE KEY `uk_dict_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典类型表';

-- 字典数据表
CREATE TABLE IF NOT EXISTS `dict_data` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
    `dict_type`  VARCHAR(100) NOT NULL COMMENT '字典类型',
    `dict_label` VARCHAR(100) NOT NULL COMMENT '字典标签',
    `dict_value` VARCHAR(100) NOT NULL COMMENT '字典值',
    `dict_sort`  INT DEFAULT 0 COMMENT '排序',
    `css_class`  VARCHAR(100) DEFAULT NULL COMMENT '样式',
    `list_class` VARCHAR(100) DEFAULT NULL COMMENT '列表样式',
    `is_default` TINYINT DEFAULT 0 COMMENT '是否默认',
    `status`     TINYINT DEFAULT 0 COMMENT '状态',
    INDEX `idx_dict_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典数据表';

-- 种子：用户性别字典。
INSERT IGNORE INTO `dict_type` (`dict_type`, `dict_name`, `status`)
VALUES ('sys_user_sex', '用户性别', 0);

INSERT INTO `dict_data` (`dict_type`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT 'sys_user_sex', '男', '0', 1, 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM `dict_data`
    WHERE `dict_type` = 'sys_user_sex' AND `dict_value` = '0'
);

INSERT INTO `dict_data` (`dict_type`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT 'sys_user_sex', '女', '1', 2, 0, 0
WHERE NOT EXISTS (
    SELECT 1 FROM `dict_data`
    WHERE `dict_type` = 'sys_user_sex' AND `dict_value` = '1'
);

-- 告警表
CREATE TABLE IF NOT EXISTS `alarms` (
    `is_deleted`     TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`        BIGINT NOT NULL COMMENT '用户ID',
    `description`    VARCHAR(500) NOT NULL COMMENT '告警描述',
    `latitude`       DOUBLE NOT NULL COMMENT '纬度',
    `longitude`      DOUBLE NOT NULL COMMENT '经度',
    `image_paths`    JSON DEFAULT NULL COMMENT '图片路径列表',
    `voice_path`     VARCHAR(500) DEFAULT NULL COMMENT '语音路径',
    `voice_duration` INT DEFAULT NULL COMMENT '语音时长(秒)',
    `status`         VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '处理状态：pending=未处理 processing=处理中 done=已处理',
    `handle_remark`  VARCHAR(500) DEFAULT NULL COMMENT '处理备注',
    `handled_by`     BIGINT DEFAULT NULL COMMENT '处理人用户ID',
    `handled_at`     DATETIME DEFAULT NULL COMMENT '处理时间',
    `created_at`     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_user` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警表';

-- 报警媒体表（媒体二进制存数据库，与报警记录同库）
CREATE TABLE IF NOT EXISTS `alarm_media` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
    `uploader_id` BIGINT DEFAULT NULL COMMENT '上传者用户ID，限制只能绑定本人媒体',
    `alarm_id`   BIGINT DEFAULT NULL COMMENT '关联报警ID；上传后未上报为 NULL，上报事务内关联',
    `media_type` VARCHAR(20) NOT NULL COMMENT '类型: image/voice',
    `mime`       VARCHAR(60) NOT NULL COMMENT 'MIME类型',
    `file_name`  VARCHAR(120) DEFAULT NULL COMMENT '原始文件名',
    `data`       LONGBLOB NOT NULL COMMENT '媒体二进制内容',
    `size`       BIGINT DEFAULT NULL COMMENT '字节数',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    INDEX `idx_alarm` (`alarm_id`),
    INDEX `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报警媒体表';

-- ============================================================
-- 三、轨迹与通话
-- ============================================================

-- 轨迹历史表（只增不改，生产环境应按 recorded_at 归档或分区）
CREATE TABLE IF NOT EXISTS `location_history` (
    `is_deleted`  TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`     BIGINT NOT NULL COMMENT '用户ID',
    `latitude`    DOUBLE NOT NULL COMMENT '纬度',
    `longitude`   DOUBLE NOT NULL COMMENT '经度',
    `speed`       DOUBLE DEFAULT NULL COMMENT '速度',
    `direction`   DOUBLE DEFAULT NULL COMMENT '方向',
    `accuracy`    DOUBLE DEFAULT NULL COMMENT '精度',
    `recorded_at` DATETIME NOT NULL COMMENT '记录时间',
    `session_id`  VARCHAR(64) DEFAULT NULL COMMENT '轨迹会话ID（UUID）',
    INDEX `idx_user_date` (`user_id`, `recorded_at`),
    INDEX `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='位置历史表';

-- 轨迹会话表
CREATE TABLE IF NOT EXISTS `trajectory_sessions` (
    `is_deleted`   TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `session_id`   VARCHAR(64) NOT NULL COMMENT '会话ID（UUID）',
    `user_id`      BIGINT NOT NULL COMMENT '用户ID',
    `name`         VARCHAR(100) DEFAULT NULL COMMENT '轨迹名称',
    `start_time`   DATETIME NOT NULL COMMENT '开始时间',
    `end_time`     DATETIME DEFAULT NULL COMMENT '结束时间',
    `distance_km`  DOUBLE DEFAULT 0 COMMENT '里程（公里）',
    `duration_sec` INT DEFAULT 0 COMMENT '时长（秒）',
    `point_count`  INT DEFAULT 0 COMMENT '轨迹点数',
    `status`       TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0=录制中 1=已结束',
    `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_session` (`session_id`),
    INDEX `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轨迹会话表';

-- 通话记录表
CREATE TABLE IF NOT EXISTS `call_records` (
    `is_deleted`       TINYINT      NOT NULL DEFAULT 0  COMMENT '逻辑删除:0=有效,1=已删除',
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `room_id`          VARCHAR(64)  NOT NULL            COMMENT '房间ID（UUID），同一房间可关联多人',
    `caller_id`        BIGINT       NOT NULL            COMMENT '主叫用户ID',
    `callee_id`        BIGINT       DEFAULT NULL        COMMENT '被叫用户ID，群组会议时为NULL',
    `media_type`       TINYINT      NOT NULL DEFAULT 1  COMMENT '1=语音 2=视频',
    `call_type`        TINYINT      NOT NULL DEFAULT 1  COMMENT '1=点对点 2=群组会议',
    `start_time`       DATETIME     DEFAULT NULL        COMMENT '开始时间',
    `end_time`         DATETIME     DEFAULT NULL        COMMENT '结束时间，NULL表示未结束',
    `duration_seconds` INT          DEFAULT 0           COMMENT '时长(秒)',
    `status`           VARCHAR(20)  NOT NULL DEFAULT 'ongoing' COMMENT 'ongoing/completed/missed/rejected',
    `record_file_url`  VARCHAR(500) DEFAULT NULL        COMMENT '录制文件路径',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_room` (`room_id`),
    INDEX `idx_caller` (`caller_id`),
    INDEX `idx_callee` (`callee_id`),
    INDEX `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通话记录表';

-- ============================================================
-- 四、设备消息与地图
-- ============================================================

-- 设备表（Mesh 基站）
CREATE TABLE IF NOT EXISTS `devices` (
    `is_deleted`    TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`          VARCHAR(100) NOT NULL COMMENT '设备名称',
    `short_name`    VARCHAR(50) DEFAULT NULL COMMENT '短名称',
    `node_addr`     VARCHAR(50) DEFAULT NULL COMMENT '节点地址',
    `node_mac`      VARCHAR(50) DEFAULT NULL COMMENT 'MAC地址',
    `node_freq`     VARCHAR(20) DEFAULT NULL COMMENT '频率',
    `node_bw`       VARCHAR(20) DEFAULT NULL COMMENT '带宽',
    `node_power`    VARCHAR(20) DEFAULT NULL COMMENT '功率',
    `distance`      VARCHAR(20) DEFAULT NULL COMMENT '距离参数',
    `mesh_id`       VARCHAR(50) DEFAULT NULL COMMENT 'Mesh ID',
    `aes_level`     INT DEFAULT 0 COMMENT 'AES加密: 0=AES-128 1=AES-256 2=禁用',
    `dhcp_enabled`  TINYINT DEFAULT 1 COMMENT 'DHCP开关',
    `dhcp_start`    VARCHAR(20) DEFAULT NULL COMMENT 'DHCP起始',
    `dhcp_end`      VARCHAR(20) DEFAULT NULL COMMENT 'DHCP结束',
    `dhcp_mask`     VARCHAR(20) DEFAULT NULL COMMENT '子网掩码',
    `dhcp_dns`      VARCHAR(20) DEFAULT NULL COMMENT 'DNS',
    `dhcp_gateway`  VARCHAR(20) DEFAULT NULL COMMENT '网关',
    `wifi_name`     VARCHAR(100) DEFAULT NULL COMMENT 'WiFi名称',
    `wifi_password` VARCHAR(100) DEFAULT NULL COMMENT 'WiFi密码',
    `created_at`    DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_node_mac` (`node_mac`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备表';

-- 语音消息表
CREATE TABLE IF NOT EXISTS `voice_messages` (
    `is_deleted`  TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `sender_id`   BIGINT NOT NULL COMMENT '发送者ID',
    `sender_name` VARCHAR(50) DEFAULT NULL COMMENT '发送者名称',
    `group_id`    BIGINT NOT NULL COMMENT '群组ID',
    `file_path`   VARCHAR(500) DEFAULT NULL COMMENT '本地文件路径',
    `duration`    INT DEFAULT NULL COMMENT '时长(秒)',
    `created_at`  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语音消息表';

-- 地图地区表
CREATE TABLE IF NOT EXISTS `map_regions` (
    `is_deleted`   TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `parent_id`    BIGINT NOT NULL DEFAULT 0 COMMENT '0=省, 非0=所属省ID',
    `name`         VARCHAR(50)  NOT NULL COMMENT '名称',
    `baidu_code`   VARCHAR(50)  DEFAULT NULL COMMENT '百度地图城市码（历史字段）',
    `adcode`       VARCHAR(12)  DEFAULT NULL COMMENT '行政区划代码（如 430600=岳阳市）',
    `bbox`         VARCHAR(64)  DEFAULT NULL COMMENT '覆盖范围：西,南,东,北',
    `map_version`  INT DEFAULT 0 COMMENT '地图包版本（每次导入+1）',
    `tile_count`   BIGINT DEFAULT 0 COMMENT '已入库瓦片张数',
    `package_size` BIGINT DEFAULT 0 COMMENT '地图包大小（字节）',
    `package_md5`  VARCHAR(32)  DEFAULT NULL COMMENT '地图包 MD5',
    `level`        TINYINT NOT NULL COMMENT '1=省 2=市',
    `size_text`    VARCHAR(20)  DEFAULT NULL COMMENT '大小文本（未导入=预估，导入后=真实）',
    `sort_order`   INT DEFAULT 0 COMMENT '排序',
    UNIQUE KEY `uk_parent_name` (`parent_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地图地区表';

-- 种子：34 个省级行政区。重复执行时跳过已存在记录。
INSERT IGNORE INTO `map_regions` (`id`, `parent_id`, `name`, `level`, `sort_order`) VALUES
(1,  0, '北京市',    1, 1),
(2,  0, '天津市',    1, 2),
(3,  0, '河北省',    1, 3),
(4,  0, '山西省',    1, 4),
(5,  0, '内蒙古自治区', 1, 5),
(6,  0, '辽宁省',    1, 6),
(7,  0, '吉林省',    1, 7),
(8,  0, '黑龙江省',  1, 8),
(9,  0, '上海市',    1, 9),
(10, 0, '江苏省',    1, 10),
(11, 0, '浙江省',    1, 11),
(12, 0, '安徽省',    1, 12),
(13, 0, '福建省',    1, 13),
(14, 0, '江西省',    1, 14),
(15, 0, '山东省',    1, 15),
(16, 0, '河南省',    1, 16),
(17, 0, '湖北省',    1, 17),
(18, 0, '湖南省',    1, 18),
(19, 0, '广东省',    1, 19),
(20, 0, '广西壮族自治区', 1, 20),
(21, 0, '海南省',    1, 21),
(22, 0, '重庆市',    1, 22),
(23, 0, '四川省',    1, 23),
(24, 0, '贵州省',    1, 24),
(25, 0, '云南省',    1, 25),
(26, 0, '西藏自治区', 1, 26),
(27, 0, '陕西省',    1, 27),
(28, 0, '甘肃省',    1, 28),
(29, 0, '青海省',    1, 29),
(30, 0, '宁夏回族自治区', 1, 30),
(31, 0, '新疆维吾尔自治区', 1, 31),
(32, 0, '台湾省',    1, 32),
(33, 0, '香港特别行政区', 1, 33),
(34, 0, '澳门特别行政区', 1, 34);

-- 种子：全国地级市、自治州和地区目录。重复执行时跳过已存在记录。
INSERT IGNORE INTO `map_regions` (`parent_id`, `name`, `baidu_code`, `level`, `size_text`, `sort_order`) VALUES
(1,  '北京市',       'beijing',      2, '约 120MB', 1),
(2,  '天津市',       'tianjin',      2, '约 90MB',  1),
(3,  '石家庄市',     'shijiazhuang', 2, '约 55MB',  1),
(3,  '唐山市',       'tangshan',     2, '约 48MB',  2),
(3,  '秦皇岛市',     'qinhuangdao',  2, '约 32MB',  3),
(3,  '邯郸市',       'handan',       2, '约 45MB',  4),
(3,  '邢台市',       'xingtai',      2, '约 40MB',  5),
(3,  '保定市',       'baoding',      2, '约 50MB',  6),
(3,  '张家口市',     'zhangjiakou',  2, '约 42MB',  7),
(3,  '承德市',       'chengde',      2, '约 38MB',  8),
(3,  '沧州市',       'cangzhou',     2, '约 40MB',  9),
(3,  '廊坊市',       'langfang',     2, '约 30MB',  10),
(3,  '衡水市',       'hengshui',     2, '约 28MB',  11),
(4,  '太原市',       'taiyuan',      2, '约 42MB',  1),
(4,  '大同市',       'datong',       2, '约 35MB',  2),
(4,  '阳泉市',       'yangquan',     2, '约 22MB',  3),
(4,  '长治市',       'changzhi',     2, '约 30MB',  4),
(4,  '晋城市',       'jincheng',     2, '约 25MB',  5),
(4,  '朔州市',       'shuozhou',     2, '约 20MB',  6),
(4,  '晋中市',       'jinzhong',     2, '约 28MB',  7),
(4,  '运城市',       'yuncheng',     2, '约 32MB',  8),
(4,  '忻州市',       'xinzhou',      2, '约 28MB',  9),
(4,  '临汾市',       'linfen',       2, '约 30MB',  10),
(4,  '吕梁市',       'lvliang',      2, '约 30MB',  11),
(5,  '呼和浩特市',   'huhehaote',    2, '约 35MB',  1),
(5,  '包头市',       'baotou',       2, '约 38MB',  2),
(5,  '乌海市',       'wuhai',        2, '约 18MB',  3),
(5,  '赤峰市',       'chifeng',      2, '约 42MB',  4),
(5,  '通辽市',       'tongliao',     2, '约 35MB',  5),
(5,  '鄂尔多斯市',   'eerduosi',     2, '约 40MB',  6),
(5,  '呼伦贝尔市',   'hulunbeier',   2, '约 55MB',  7),
(5,  '巴彦淖尔市',   'bayannaoer',   2, '约 32MB',  8),
(5,  '乌兰察布市',   'wulanchabu',   2, '约 35MB',  9),
(5,  '兴安盟',       'xinganmeng',   2, '约 28MB',  10),
(5,  '锡林郭勒盟',   'xilinguole',   2, '约 48MB',  11),
(5,  '阿拉善盟',     'alashan',      2, '约 38MB',  12),
(6,  '沈阳市',       'shenyang',     2, '约 55MB',  1),
(6,  '大连市',       'dalian',       2, '约 52MB',  2),
(6,  '鞍山市',       'anshan',       2, '约 35MB',  3),
(6,  '抚顺市',       'fushun',       2, '约 30MB',  4),
(6,  '本溪市',       'benxi',        2, '约 25MB',  5),
(6,  '丹东市',       'dandong',      2, '约 32MB',  6),
(6,  '锦州市',       'jinzhou',      2, '约 30MB',  7),
(6,  '营口市',       'yingkou',      2, '约 28MB',  8),
(6,  '阜新市',       'fuxin',        2, '约 22MB',  9),
(6,  '辽阳市',       'liaoyang',     2, '约 24MB',  10),
(6,  '盘锦市',       'panjin',       2, '约 20MB',  11),
(6,  '铁岭市',       'tieling',      2, '约 30MB',  12),
(6,  '朝阳市',       'chaoyang',     2, '约 28MB',  13),
(6,  '葫芦岛市',     'huludao',      2, '约 25MB',  14),
(7,  '长春市',       'changchun',    2, '约 50MB',  1),
(7,  '吉林市',       'jilin',        2, '约 42MB',  2),
(7,  '四平市',       'siping',       2, '约 28MB',  3),
(7,  '辽源市',       'liaoyuan',     2, '约 20MB',  4),
(7,  '通化市',       'tonghua',      2, '约 30MB',  5),
(7,  '白山市',       'baishan',      2, '约 28MB',  6),
(7,  '松原市',       'songyuan',     2, '约 32MB',  7),
(7,  '白城市',       'baicheng',     2, '约 28MB',  8),
(7,  '延边朝鲜族自治州', 'yanbian',  2, '约 35MB',  9),
(8,  '哈尔滨市',     'haerbin',      2, '约 62MB',  1),
(8,  '齐齐哈尔市',   'qiqihaer',     2, '约 45MB',  2),
(8,  '鸡西市',       'jixi',         2, '约 28MB',  3),
(8,  '鹤岗市',       'hegang',       2, '约 22MB',  4),
(8,  '双鸭山市',     'shuangyashan', 2, '约 25MB',  5),
(8,  '大庆市',       'daqing',       2, '约 35MB',  6),
(8,  '伊春市',       'yichun',       2, '约 32MB',  7),
(8,  '佳木斯市',     'jiamusi',      2, '约 35MB',  8),
(8,  '七台河市',     'qitaihe',      2, '约 18MB',  9),
(8,  '牡丹江市',     'mudanjiang',   2, '约 35MB',  10),
(8,  '黑河市',       'heihe',        2, '约 32MB',  11),
(8,  '绥化市',       'suihua',       2, '约 38MB',  12),
(8,  '大兴安岭地区', 'daxinganling', 2, '约 30MB',  13),
(9,  '上海市',       'shanghai',     2, '约 150MB', 1),
(10, '南京市',       'nanjing',      2, '约 58MB',  1),
(10, '无锡市',       'wuxi',         2, '约 42MB',  2),
(10, '徐州市',       'xuzhou',       2, '约 40MB',  3),
(10, '常州市',       'changzhou',    2, '约 38MB',  4),
(10, '苏州市',       'suzhou',       2, '约 52MB',  5),
(10, '南通市',       'nantong',      2, '约 38MB',  6),
(10, '连云港市',     'lianyungang',  2, '约 32MB',  7),
(10, '淮安市',       'huaian',       2, '约 35MB',  8),
(10, '盐城市',       'yancheng',     2, '约 40MB',  9),
(10, '扬州市',       'yangzhou',     2, '约 35MB',  10),
(10, '镇江市',       'zhenjiang',    2, '约 28MB',  11),
(10, '泰州市',       'taizhou',      2, '约 30MB',  12),
(10, '宿迁市',       'suqian',       2, '约 28MB',  13),
(11, '杭州市',       'hangzhou',     2, '约 55MB',  1),
(11, '宁波市',       'ningbo',       2, '约 48MB',  2),
(11, '温州市',       'wenzhou',      2, '约 42MB',  3),
(11, '嘉兴市',       'jiaxing',      2, '约 32MB',  4),
(11, '湖州市',       'huzhou',       2, '约 28MB',  5),
(11, '绍兴市',       'shaoxing',     2, '约 35MB',  6),
(11, '金华市',       'jinhua',       2, '约 38MB',  7),
(11, '衢州市',       'quzhou',       2, '约 28MB',  8),
(11, '舟山市',       'zhoushan',     2, '约 22MB',  9),
(11, '台州市',       'taizhou_zj',   2, '约 38MB',  10),
(11, '丽水市',       'lishui',       2, '约 32MB',  11),
(12, '合肥市',       'hefei',        2, '约 48MB',  1),
(12, '芜湖市',       'wuhu',         2, '约 32MB',  2),
(12, '蚌埠市',       'bengbu',       2, '约 28MB',  3),
(12, '淮南市',       'huainan',      2, '约 25MB',  4),
(12, '马鞍山市',     'maanshan',     2, '约 24MB',  5),
(12, '淮北市',       'huaibei',      2, '约 22MB',  6),
(12, '铜陵市',       'tongling',     2, '约 18MB',  7),
(12, '安庆市',       'anqing',       2, '约 35MB',  8),
(12, '黄山市',       'huangshan',    2, '约 28MB',  9),
(12, '滁州市',       'chuzhou',      2, '约 32MB',  10),
(12, '阜阳市',       'fuyang',       2, '约 35MB',  11),
(12, '宿州市',       'suzhou_ah',    2, '约 30MB',  12),
(12, '六安市',       'liuan',        2, '约 35MB',  13),
(12, '亳州市',       'bozhou',       2, '约 28MB',  14),
(12, '池州市',       'chizhou',      2, '约 24MB',  15),
(12, '宣城市',       'xuancheng',    2, '约 28MB',  16),
(13, '福州市',       'fuzhou',       2, '约 45MB',  1),
(13, '厦门市',       'xiamen',       2, '约 32MB',  2),
(13, '莆田市',       'putian',       2, '约 25MB',  3),
(13, '三明市',       'sanming',      2, '约 32MB',  4),
(13, '泉州市',       'quanzhou',     2, '约 38MB',  5),
(13, '漳州市',       'zhangzhou',    2, '约 35MB',  6),
(13, '南平市',       'nanping',      2, '约 32MB',  7),
(13, '龙岩市',       'longyan',      2, '约 30MB',  8),
(13, '宁德市',       'ningde',       2, '约 30MB',  9),
(14, '南昌市',       'nanchang',     2, '约 42MB',  1),
(14, '景德镇市',     'jingdezhen',   2, '约 22MB',  2),
(14, '萍乡市',       'pingxiang',    2, '约 20MB',  3),
(14, '九江市',       'jiujiang',     2, '约 35MB',  4),
(14, '新余市',       'xinyu',        2, '约 18MB',  5),
(14, '鹰潭市',       'yingtan',      2, '约 15MB',  6),
(14, '赣州市',       'ganzhou',      2, '约 42MB',  7),
(14, '吉安市',       'jian',         2, '约 32MB',  8),
(14, '宜春市',       'yichun_jx',    2, '约 30MB',  9),
(14, '抚州市',       'fuzhou_jx',    2, '约 28MB',  10),
(14, '上饶市',       'shangrao',     2, '约 35MB',  11),
(15, '济南市',       'jinan',        2, '约 52MB',  1),
(15, '青岛市',       'qingdao',      2, '约 50MB',  2),
(15, '淄博市',       'zibo',         2, '约 35MB',  3),
(15, '枣庄市',       'zaozhuang',    2, '约 28MB',  4),
(15, '东营市',       'dongying',     2, '约 30MB',  5),
(15, '烟台市',       'yantai',       2, '约 42MB',  6),
(15, '潍坊市',       'weifang',      2, '约 40MB',  7),
(15, '济宁市',       'jining',       2, '约 38MB',  8),
(15, '泰安市',       'taian',        2, '约 32MB',  9),
(15, '威海市',       'weihai',       2, '约 28MB',  10),
(15, '日照市',       'rizhao',       2, '约 25MB',  11),
(15, '临沂市',       'linyi',        2, '约 42MB',  12),
(15, '德州市',       'dezhou',       2, '约 32MB',  13),
(15, '聊城市',       'liaocheng',    2, '约 30MB',  14),
(15, '滨州市',       'binzhou',      2, '约 28MB',  15),
(15, '菏泽市',       'heze',         2, '约 35MB',  16),
(16, '郑州市',       'zhengzhou',    2, '约 52MB',  1),
(16, '开封市',       'kaifeng',      2, '约 30MB',  2),
(16, '洛阳市',       'luoyang',      2, '约 38MB',  3),
(16, '平顶山市',     'pingdingshan', 2, '约 32MB',  4),
(16, '安阳市',       'anyang',       2, '约 30MB',  5),
(16, '鹤壁市',       'hebi',         2, '约 18MB',  6),
(16, '新乡市',       'xinxiang',     2, '约 32MB',  7),
(16, '焦作市',       'jiaozuo',      2, '约 25MB',  8),
(16, '濮阳市',       'puyang',       2, '约 25MB',  9),
(16, '许昌市',       'xuchang',      2, '约 28MB',  10),
(16, '漯河市',       'luohe',        2, '约 20MB',  11),
(16, '三门峡市',     'sanmenxia',    2, '约 22MB',  12),
(16, '南阳市',       'nanyang',      2, '约 40MB',  13),
(16, '商丘市',       'shangqiu',     2, '约 35MB',  14),
(16, '信阳市',       'xinyang',      2, '约 32MB',  15),
(16, '周口市',       'zhoukou',      2, '约 35MB',  16),
(16, '驻马店市',     'zhumadian',    2, '约 32MB',  17),
(16, '济源市',       'jiyuan',       2, '约 15MB',  18),
(17, '武汉市',       'wuhan',        2, '约 58MB',  1),
(17, '黄石市',       'huangshi',     2, '约 25MB',  2),
(17, '十堰市',       'shiyan',       2, '约 35MB',  3),
(17, '宜昌市',       'yichang',      2, '约 38MB',  4),
(17, '襄阳市',       'xiangyang',    2, '约 35MB',  5),
(17, '鄂州市',       'ezhou',        2, '约 18MB',  6),
(17, '荆门市',       'jingmen',      2, '约 25MB',  7),
(17, '孝感市',       'xiaogan',      2, '约 30MB',  8),
(17, '荆州市',       'jingzhou',     2, '约 32MB',  9),
(17, '黄冈市',       'huanggang',    2, '约 35MB',  10),
(17, '咸宁市',       'xianning',     2, '约 25MB',  11),
(17, '随州市',       'suizhou',      2, '约 22MB',  12),
(17, '恩施土家族苗族自治州', 'enshi', 2, '约 30MB', 13),
(17, '仙桃市',       'xiantao',      2, '约 12MB',  14),
(17, '潜江市',       'qianjiang',    2, '约 10MB',  15),
(17, '天门市',       'tianmen',      2, '约 10MB',  16),
(17, '神农架林区',   'shennongjia',  2, '约 8MB',   17),
(18, '长沙市',       'changsha',     2, '约 48MB',  1),
(18, '株洲市',       'zhuzhou',      2, '约 32MB',  2),
(18, '湘潭市',       'xiangtan',     2, '约 28MB',  3),
(18, '衡阳市',       'hengyang',     2, '约 35MB',  4),
(18, '邵阳市',       'shaoyang',     2, '约 30MB',  5),
(18, '岳阳市',       'yueyang',      2, '约 33MB',  6),
(18, '常德市',       'changde',      2, '约 33MB',  7),
(18, '张家界市',     'zhangjiajie',  2, '约 25MB',  8),
(18, '益阳市',       'yiyang',       2, '约 29MB',  9),
(18, '郴州市',       'chenzhou',     2, '约 28MB',  10),
(18, '永州市',       'yongzhou',     2, '约 31MB',  11),
(18, '怀化市',       'huaihua',      2, '约 27MB',  12),
(18, '娄底市',       'loudi',        2, '约 26MB',  13),
(18, '湘西土家族苗族自治州', 'xiangxi', 2, '约 22MB', 14),
(19, '广州市',       'guangzhou',    2, '约 65MB',  1),
(19, '韶关市',       'shaoguan',     2, '约 30MB',  2),
(19, '深圳市',       'shenzhen',     2, '约 55MB',  3),
(19, '珠海市',       'zhuhai',       2, '约 28MB',  4),
(19, '汕头市',       'shantou',      2, '约 30MB',  5),
(19, '佛山市',       'foshan',       2, '约 42MB',  6),
(19, '江门市',       'jiangmen',     2, '约 35MB',  7),
(19, '湛江市',       'zhanjiang',    2, '约 35MB',  8),
(19, '茂名市',       'maoming',      2, '约 32MB',  9),
(19, '肇庆市',       'zhaoqing',     2, '约 32MB',  10),
(19, '惠州市',       'huizhou',      2, '约 35MB',  11),
(19, '梅州市',       'meizhou',      2, '约 30MB',  12),
(19, '汕尾市',       'shanwei',      2, '约 22MB',  13),
(19, '河源市',       'heyuan',       2, '约 28MB',  14),
(19, '阳江市',       'yangjiang',    2, '约 25MB',  15),
(19, '清远市',       'qingyuan',     2, '约 32MB',  16),
(19, '东莞市',       'dongguan',     2, '约 38MB',  17),
(19, '中山市',       'zhongshan',    2, '约 25MB',  18),
(19, '潮州市',       'chaozhou',     2, '约 18MB',  19),
(19, '揭阳市',       'jieyang',      2, '约 25MB',  20),
(19, '云浮市',       'yunfu',        2, '约 22MB',  21),
(20, '南宁市',       'nanning',      2, '约 45MB',  1),
(20, '柳州市',       'liuzhou',      2, '约 35MB',  2),
(20, '桂林市',       'guilin',       2, '约 38MB',  3),
(20, '梧州市',       'wuzhou',       2, '约 28MB',  4),
(20, '北海市',       'beihai',       2, '约 22MB',  5),
(20, '防城港市',     'fangchenggang',2, '约 18MB',  6),
(20, '钦州市',       'qinzhou',      2, '约 25MB',  7),
(20, '贵港市',       'guigang',      2, '约 25MB',  8),
(20, '玉林市',       'yulin',        2, '约 30MB',  9),
(20, '百色市',       'baise',        2, '约 35MB',  10),
(20, '贺州市',       'hezhou',       2, '约 22MB',  11),
(20, '河池市',       'hechi',        2, '约 30MB',  12),
(20, '来宾市',       'laibin',       2, '约 25MB',  13),
(20, '崇左市',       'chongzuo',     2, '约 22MB',  14),
(21, '海口市',       'haikou',       2, '约 30MB',  1),
(21, '三亚市',       'sanya',        2, '约 22MB',  2),
(21, '三沙市',       'sansha',       2, '约 5MB',   3),
(21, '儋州市',       'danzhou',      2, '约 20MB',  4),
(21, '五指山市',     'wuzhishan',    2, '约 8MB',   5),
(21, '琼海市',       'qionghai',     2, '约 12MB',  6),
(21, '文昌市',       'wenchang',     2, '约 14MB',  7),
(21, '万宁市',       'wanning',      2, '约 12MB',  8),
(21, '东方市',       'dongfang',     2, '约 12MB',  9),
(21, '定安县',       'dingan',       2, '约 8MB',   10),
(21, '屯昌县',       'tunchang',     2, '约 8MB',   11),
(21, '澄迈县',       'chengmai',     2, '约 10MB',  12),
(21, '临高县',       'lingao',       2, '约 8MB',   13),
(21, '白沙黎族自治县', 'baisha',     2, '约 6MB',   14),
(21, '昌江黎族自治县', 'changjiang', 2, '约 6MB',   15),
(21, '乐东黎族自治县', 'ledong',     2, '约 8MB',   16),
(21, '陵水黎族自治县', 'lingshui',   2, '约 8MB',   17),
(21, '保亭黎族苗族自治县', 'baoting', 2, '约 6MB',  18),
(21, '琼中黎族苗族自治县', 'qiongzhong', 2, '约 6MB', 19),
(22, '重庆市',       'chongqing',    2, '约 110MB', 1),
(23, '成都市',       'chengdu',      2, '约 58MB',  1),
(23, '自贡市',       'zigong',       2, '约 20MB',  2),
(23, '攀枝花市',     'panzhihua',    2, '约 18MB',  3),
(23, '泸州市',       'luzhou',       2, '约 28MB',  4),
(23, '德阳市',       'deyang',       2, '约 25MB',  5),
(23, '绵阳市',       'mianyang',     2, '约 35MB',  6),
(23, '广元市',       'guangyuan',    2, '约 28MB',  7),
(23, '遂宁市',       'suining',      2, '约 22MB',  8),
(23, '内江市',       'neijiang',     2, '约 22MB',  9),
(23, '乐山市',       'leshan',       2, '约 30MB',  10),
(23, '南充市',       'nanchong',     2, '约 32MB',  11),
(23, '眉山市',       'meishan',      2, '约 22MB',  12),
(23, '宜宾市',       'yibin',        2, '约 30MB',  13),
(23, '广安市',       'guangan',      2, '约 25MB',  14),
(23, '达州市',       'dazhou',       2, '约 30MB',  15),
(23, '雅安市',       'yaan',         2, '约 22MB',  16),
(23, '巴中市',       'bazhong',      2, '约 25MB',  17),
(23, '资阳市',       'ziyang',       2, '约 20MB',  18),
(23, '阿坝藏族羌族自治州', 'aba',    2, '约 28MB',  19),
(23, '甘孜藏族自治州', 'ganzi',      2, '约 35MB',  20),
(23, '凉山彝族自治州', 'liangshan',  2, '约 32MB',  21),
(24, '贵阳市',       'guiyang',      2, '约 38MB',  1),
(24, '六盘水市',     'liupanshui',   2, '约 25MB',  2),
(24, '遵义市',       'zunyi',        2, '约 35MB',  3),
(24, '安顺市',       'anshun',       2, '约 22MB',  4),
(24, '毕节市',       'bijie',        2, '约 32MB',  5),
(24, '铜仁市',       'tongren',      2, '约 28MB',  6),
(24, '黔西南布依族苗族自治州', 'qianxinan', 2, '约 25MB', 7),
(24, '黔东南苗族侗族自治州', 'qiandongnan', 2, '约 28MB', 8),
(24, '黔南布依族苗族自治州', 'qiannan', 2, '约 25MB', 9),
(25, '昆明市',       'kunming',      2, '约 48MB',  1),
(25, '曲靖市',       'qujing',       2, '约 35MB',  2),
(25, '玉溪市',       'yuxi',         2, '约 25MB',  3),
(25, '保山市',       'baoshan',      2, '约 28MB',  4),
(25, '昭通市',       'zhaotong',     2, '约 30MB',  5),
(25, '丽江市',       'lijiang',      2, '约 25MB',  6),
(25, '普洱市',       'puer',         2, '约 30MB',  7),
(25, '临沧市',       'lincang',      2, '约 25MB',  8),
(25, '楚雄彝族自治州', 'chuxiong',   2, '约 28MB',  9),
(25, '红河哈尼族彝族自治州', 'honghe', 2, '约 32MB', 10),
(25, '文山壮族苗族自治州', 'wenshan', 2, '约 28MB', 11),
(25, '西双版纳傣族自治州', 'xishuangbanna', 2, '约 22MB', 12),
(25, '大理白族自治州', 'dali',       2, '约 28MB',  13),
(25, '德宏傣族景颇族自治州', 'dehong', 2, '约 20MB', 14),
(25, '怒江傈僳族自治州', 'nujiang', 2, '约 18MB',  15),
(25, '迪庆藏族自治州', 'diqing',     2, '约 15MB',  16),
(26, '拉萨市',       'lasa',         2, '约 25MB',  1),
(26, '日喀则市',     'rikaze',       2, '约 30MB',  2),
(26, '昌都市',       'changdu',      2, '约 28MB',  3),
(26, '林芝市',       'linzhi',       2, '约 22MB',  4),
(26, '山南市',       'shannan',      2, '约 20MB',  5),
(26, '那曲市',       'naqu',         2, '约 32MB',  6),
(26, '阿里地区',     'ali',          2, '约 25MB',  7),
(27, '西安市',       'xian',         2, '约 55MB',  1),
(27, '铜川市',       'tongchuan',    2, '约 18MB',  2),
(27, '宝鸡市',       'baoji',        2, '约 30MB',  3),
(27, '咸阳市',       'xianyang',     2, '约 32MB',  4),
(27, '渭南市',       'weinan',       2, '约 30MB',  5),
(27, '延安市',       'yanan',        2, '约 28MB',  6),
(27, '汉中市',       'hanzhong',     2, '约 30MB',  7),
(27, '榆林市',       'yulin_sx',     2, '约 35MB',  8),
(27, '安康市',       'ankang',       2, '约 28MB',  9),
(27, '商洛市',       'shangluo',     2, '约 22MB',  10),
(28, '兰州市',       'lanzhou',      2, '约 35MB',  1),
(28, '嘉峪关市',     'jiayuguan',    2, '约 10MB',  2),
(28, '金昌市',       'jinchang',     2, '约 12MB',  3),
(28, '白银市',       'baiyin',       2, '约 22MB',  4),
(28, '天水市',       'tianshui',     2, '约 28MB',  5),
(28, '武威市',       'wuwei',        2, '约 25MB',  6),
(28, '张掖市',       'zhangye',      2, '约 28MB',  7),
(28, '平凉市',       'pingliang',    2, '约 22MB',  8),
(28, '酒泉市',       'jiuquan',      2, '约 35MB',  9),
(28, '庆阳市',       'qingyang',     2, '约 25MB',  10),
(28, '定西市',       'dingxi',       2, '约 22MB',  11),
(28, '陇南市',       'longnan',      2, '约 25MB',  12),
(28, '临夏回族自治州', 'linxia',     2, '约 18MB',  13),
(28, '甘南藏族自治州', 'gannan',     2, '约 18MB',  14),
(29, '西宁市',       'xining',       2, '约 30MB',  1),
(29, '海东市',       'haidong',      2, '约 22MB',  2),
(29, '海北藏族自治州', 'haibei',     2, '约 18MB',  3),
(29, '黄南藏族自治州', 'huangnan',   2, '约 15MB',  4),
(29, '海南藏族自治州', 'hainan',     2, '约 18MB',  5),
(29, '果洛藏族自治州', 'guoluo',     2, '约 18MB',  6),
(29, '玉树藏族自治州', 'yushu',      2, '约 20MB',  7),
(29, '海西蒙古族藏族自治州', 'haixi', 2, '约 28MB', 8),
(30, '银川市',       'yinchuan',     2, '约 32MB',  1),
(30, '石嘴山市',     'shizuishan',   2, '约 18MB',  2),
(30, '吴忠市',       'wuzhong',      2, '约 22MB',  3),
(30, '固原市',       'guyuan',       2, '约 20MB',  4),
(30, '中卫市',       'zhongwei',     2, '约 18MB',  5),
(31, '乌鲁木齐市',   'wulumuqi',     2, '约 42MB',  1),
(31, '克拉玛依市',   'kelamayi',     2, '约 18MB',  2),
(31, '吐鲁番市',     'tulufan',      2, '约 20MB',  3),
(31, '哈密市',       'hami',         2, '约 25MB',  4),
(31, '昌吉回族自治州', 'changji',    2, '约 28MB',  5),
(31, '博尔塔拉蒙古自治州', 'boertala', 2, '约 18MB', 6),
(31, '巴音郭楞蒙古自治州', 'bayinguoleng', 2, '约 38MB', 7),
(31, '阿克苏地区',   'akesu',        2, '约 30MB',  8),
(31, '克孜勒苏柯尔克孜自治州', 'kezilesu', 2, '约 15MB', 9),
(31, '喀什地区',     'kashi',        2, '约 35MB',  10),
(31, '和田地区',     'hetian',       2, '约 32MB',  11),
(31, '伊犁哈萨克自治州', 'yili',     2, '约 35MB',  12),
(31, '塔城地区',     'tacheng',      2, '约 22MB',  13),
(31, '阿勒泰地区',   'aletai',       2, '约 25MB',  14),
(31, '石河子市',     'shihezi',      2, '约 10MB',  15),
(31, '阿拉尔市',     'alaer',        2, '约 8MB',   16),
(31, '图木舒克市',   'tumushuke',    2, '约 8MB',   17),
(31, '五家渠市',     'wujiaqu',      2, '约 6MB',   18),
(31, '北屯市',       'beitun',       2, '约 6MB',   19),
(31, '铁门关市',     'tiemenguan',   2, '约 6MB',   20),
(31, '双河市',       'shuanghe',     2, '约 6MB',   21),
(31, '可克达拉市',   'kekedala',     2, '约 6MB',   22),
(31, '昆玉市',       'kunyu',        2, '约 6MB',   23),
(31, '胡杨河市',     'huyanghe',     2, '约 6MB',   24),
(32, '台北市',       'taibei',       2, '约 25MB',  1),
(32, '高雄市',       'gaoxiong',     2, '约 22MB',  2),
(32, '台中市',       'taizhong',     2, '约 20MB',  3),
(32, '台南市',       'tainan',       2, '约 18MB',  4),
(32, '基隆市',       'jilong',       2, '约 8MB',   5),
(32, '新竹市',       'xinzhu',       2, '约 10MB',  6),
(32, '嘉义市',       'jiayi',        2, '约 8MB',   7),
(33, '香港',         'hongkong',     2, '约 35MB',  1),
(34, '澳门',         'macau',        2, '约 10MB',  1);

-- 补全岳阳市的行政区划代码和地图包围盒。
INSERT INTO `map_regions` (`parent_id`, `name`, `baidu_code`, `adcode`, `bbox`, `level`, `size_text`, `sort_order`)
VALUES (18, '岳阳市', NULL, '430600', '112.14,28.34,114.26,29.96', 2, '未导入', 6)
ON DUPLICATE KEY UPDATE
  `adcode` = VALUES(`adcode`),
  `bbox`   = VALUES(`bbox`);

-- 地图下载记录表
CREATE TABLE IF NOT EXISTS `map_downloads` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`    BIGINT NOT NULL COMMENT '用户ID',
    `region_id`  BIGINT NOT NULL COMMENT '城市ID',
    `status`     VARCHAR(20) NOT NULL DEFAULT 'downloading' COMMENT 'downloading/completed/failed',
    `progress`   INT DEFAULT 0 COMMENT '进度 0-100',
    `file_size`  BIGINT DEFAULT NULL COMMENT '实际下载字节数',
    `error_msg`  VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_region` (`user_id`, `region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地图下载记录表';

-- 离线地图瓦片表（一张瓦片一行，BLOB 存数据）
-- 单行 BLOB 受 max_allowed_packet 限制，生产环境应适当调大该参数。
CREATE TABLE IF NOT EXISTS `map_tiles` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `region_id` BIGINT NOT NULL COMMENT '对应 map_regions.id',
    `z`         TINYINT UNSIGNED NOT NULL COMMENT '缩放级别',
    `x`         INT UNSIGNED NOT NULL COMMENT '列号',
    `y`         INT UNSIGNED NOT NULL COMMENT '行号（XYZ 标准，从上往下）',
    `tile_data` LONGBLOB NOT NULL COMMENT '瓦片数据（矢量瓦片 .pbf 或栅格 .png）',
    `size`      INT NOT NULL COMMENT '字节数（统计用）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_region_zxy` (`region_id`, `z`, `x`, `y`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='离线地图瓦片表';

-- 用户位置表
CREATE TABLE IF NOT EXISTS `user_locations` (
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`    BIGINT NOT NULL COMMENT '用户ID',
    `latitude`   DOUBLE NOT NULL COMMENT '纬度',
    `longitude`  DOUBLE NOT NULL COMMENT '经度',
    `address`    VARCHAR(255) DEFAULT NULL COMMENT '地址描述',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '上报时间',
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户位置表';

-- ============================================================
-- 五、聊天消息与手机摄像头
-- ============================================================

-- 手机摄像头设备表
CREATE TABLE IF NOT EXISTS `phone_cameras` (
    `is_deleted`     TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    `user_id`        BIGINT NOT NULL COMMENT '所属用户ID',
    `device_name`    VARCHAR(100) DEFAULT NULL COMMENT '设备名称（手机型号）',
    `device_id`      VARCHAR(255) NOT NULL COMMENT '设备唯一标识',
    `status`         TINYINT DEFAULT 0 COMMENT '0=离线 1=在线 2=推流中',
    `hls_url`        VARCHAR(500) DEFAULT NULL COMMENT 'HLS播放地址',
    `mediamtx_path`  VARCHAR(100) DEFAULT NULL COMMENT 'MediaMTX路径名',
    `last_heartbeat` DATETIME DEFAULT NULL COMMENT '最后心跳时间',
    `created_at`     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_device_id` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='手机摄像头设备表';

-- 聊天消息表
CREATE TABLE IF NOT EXISTS `chat_messages` (
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `sender_id`   BIGINT       NOT NULL COMMENT '发送者用户ID',
    `receiver_id` BIGINT       NOT NULL COMMENT '接收者用户ID',
    `content`     TEXT         NOT NULL COMMENT '消息内容',
    `msg_type`    VARCHAR(20)  NOT NULL DEFAULT 'text' COMMENT '消息类型: text/call/image/video',
    `fp`          VARCHAR(64)  DEFAULT NULL COMMENT '消息指纹(客户端QoS fp,判重用)',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    INDEX `idx_sender_receiver` (`sender_id`, `receiver_id`, `created_at`),
    INDEX `idx_receiver_sender` (`receiver_id`, `sender_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';

-- 群组聊天消息表
CREATE TABLE IF NOT EXISTS `group_messages` (
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=有效,1=已删除',
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `group_id`    BIGINT       NOT NULL COMMENT '群组ID',
    `sender_id`   BIGINT       NOT NULL COMMENT '发送者用户ID',
    `sender_name` VARCHAR(50)  DEFAULT NULL COMMENT '发送者名称',
    `content`     TEXT         NOT NULL COMMENT '消息内容',
    `msg_type`    VARCHAR(20)  NOT NULL DEFAULT 'text' COMMENT '消息类型: text/image/video',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    INDEX `idx_group_time` (`group_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群组聊天消息表';


