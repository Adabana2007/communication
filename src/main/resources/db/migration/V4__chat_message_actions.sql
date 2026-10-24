-- 按账号隐藏消息；撤回状态与变更日志供离线客户端补同步。
ALTER TABLE chat_messages ADD COLUMN recalled_at DATETIME DEFAULT NULL;
ALTER TABLE group_messages ADD COLUMN fp VARCHAR(100) DEFAULT NULL,
    ADD COLUMN recalled_at DATETIME DEFAULT NULL,
    ADD INDEX idx_group_message_fp (sender_id, fp);

CREATE TABLE chat_message_actions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    chat_type VARCHAR(10) NOT NULL,
    message_id BIGINT NOT NULL,
    fp VARCHAR(100) DEFAULT NULL,
    peer_id BIGINT DEFAULT NULL,
    group_id BIGINT DEFAULT NULL,
    action VARCHAR(10) NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_message_action (user_id, chat_type, message_id, action),
    INDEX idx_action_sync (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
