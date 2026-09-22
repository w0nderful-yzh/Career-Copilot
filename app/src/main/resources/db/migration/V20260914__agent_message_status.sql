-- agent_messages.completed（BOOLEAN，建表后始终为默认 TRUE，从未被写入或读取）替换为三态 status。
--
-- 引入原因（P1 待收口）：客户端「停止生成」后会中断 SSE 连接，本轮落库此前依赖流式生成器的
-- finally 分支，中断路径下根本不执行，导致刷新后整轮对话消失；同时 completed 无法表达
-- 「用户主动停止」与「生成失败」的区别。三态语义：
--   COMPLETED 正常完成
--   STOPPED   用户主动停止（含连接中断）：content 为已生成的部分内容，允许为空
--   FAILED    生成过程异常（如 LLM 调用失败）
ALTER TABLE agent_messages ADD COLUMN status VARCHAR(20);

-- 历史数据回填：completed 恒为 TRUE，等价于 COMPLETED；用户消息本身即完整输入，同样为 COMPLETED。
UPDATE agent_messages SET status = 'COMPLETED';

ALTER TABLE agent_messages ALTER COLUMN status SET NOT NULL;

ALTER TABLE agent_messages
  ADD CONSTRAINT agent_messages_status_check
  CHECK (status IN ('COMPLETED', 'STOPPED', 'FAILED'));

ALTER TABLE agent_messages DROP COLUMN completed;
