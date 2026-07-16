-- 现有开发数据库升级脚本；新环境由 db/schema.sql 直接创建这些字段。
ALTER TABLE know_posts
    ADD COLUMN rag_index_status VARCHAR(16) NOT NULL DEFAULT 'NOT_INDEXED'
        COMMENT 'NOT_INDEXED/PENDING/INDEXING/READY/FAILED' AFTER status,
    ADD COLUMN rag_index_error VARCHAR(512) NULL
        COMMENT '可安全展示的索引失败原因' AFTER rag_index_status,
    ADD COLUMN rag_index_chunk_count INT UNSIGNED NOT NULL DEFAULT 0
        AFTER rag_index_error,
    ADD COLUMN rag_indexed_at TIMESTAMP NULL DEFAULT NULL
        AFTER rag_index_chunk_count,
    ADD KEY ix_know_posts_rag_status (rag_index_status, update_time);
