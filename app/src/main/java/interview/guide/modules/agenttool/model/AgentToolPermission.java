package interview.guide.modules.agenttool.model;

/**
 * Agent Tool 权限等级。
 *
 * <p>READ：只读操作，Agent 可直接调用。
 * <p>SAFE_WRITE：低风险写入，可自动执行但必须可审计。
 * <p>CONFIRM_WRITE：具有业务副作用的写入，必须用户确认后执行。
 */
public enum AgentToolPermission {
  READ,
  SAFE_WRITE,
  CONFIRM_WRITE
}