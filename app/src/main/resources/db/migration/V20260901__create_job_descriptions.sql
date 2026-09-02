-- P2-5：JD 附件（job_descriptions）+ 会话绑定 active_job_id
-- JD 与简历分离存储：复用 Tika 文本解析，但不进简历库（不动 hash/去重语义）

CREATE TABLE job_descriptions (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    company VARCHAR(255),
    content_text TEXT NOT NULL,
    file_key VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_job_descriptions_created_at ON job_descriptions (created_at DESC);

-- 会话绑定活动 JD（对称 active_resume_id，Conversation Memory）
ALTER TABLE agent_conversations ADD COLUMN active_job_id BIGINT;
