package interview.guide.modules.agenttool.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent Tool 参数的语义说明（ARCH-1 契约的一部分）。
 *
 * <p>写在请求模型的 record component 上，由 {@link
 * interview.guide.modules.agenttool.contract.AgentToolSchemaExporter} 带进导出的 JSON Schema。
 * 这里写的是**给调用方看的口径**（可选性与互斥关系、单位、裁剪上限），不是代码复述。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ToolParam {

  /** 参数语义与使用口径 */
  String value();
}
