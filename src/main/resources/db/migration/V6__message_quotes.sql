-- 引用只保存原消息ID，内容查询时生成，避免撤回后残留内容快照。
ALTER TABLE chat_messages ADD COLUMN quote_message_id BIGINT NULL;
CREATE INDEX idx_chat_quote ON chat_messages (quote_message_id);
ALTER TABLE group_messages ADD COLUMN quote_message_id BIGINT NULL;
CREATE INDEX idx_group_quote ON group_messages (quote_message_id);
