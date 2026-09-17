package interview.guide.modules.agenttool.contract;

import interview.guide.modules.agenttool.model.AgentToolName;
import interview.guide.modules.agenttool.model.ToolParam;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Tool 的 JSON Schema 导出器（ARCH-1）。
 *
 * <p>从 {@link interview.guide.modules.agenttool.model.AgentToolRequests} 的请求 record
 * 直接导出 JSON Schema——这是「契约单一事实源」的产出方式：类型模型是源，
 * Schema 是产物，用产物做 Java 侧校验、Tool Discovery 暴露、Python 侧调用前校验与 CI 漂移检查。
 *
 * <p>导出结果必须**稳定且可 diff**（同一份模型永远导出同一份内容），因此字段顺序沿用
 * record 声明顺序；嵌套深度有上限，避免异常模型导致无限递归。
 */
public final class AgentToolSchemaExporter {

  /** 契约版本：字段语义发生不兼容变化时递增，便于调用方识别 */
  public static final int CONTRACT_VERSION = 1;

  /** 契约源文件（写进产物，便于调用方回溯） */
  public static final String SOURCE_FILE =
      "app/src/main/java/interview/guide/modules/agenttool/model/AgentToolRequests.java";

  /** 嵌套对象展开深度上限 */
  private static final int MAX_DEPTH = 4;

  private AgentToolSchemaExporter() {}

  /** 全量契约：{@code {version, source, tools:{name: schema}}} */
  public static Map<String, Object> exportContract() {
    Map<String, Object> tools = new LinkedHashMap<>();
    for (AgentToolName tool : AgentToolName.values()) {
      tools.put(tool.getName(), exportTool(tool));
    }
    Map<String, Object> contract = new LinkedHashMap<>();
    contract.put("version", CONTRACT_VERSION);
    contract.put("source", SOURCE_FILE);
    contract.put("tools", tools);
    return contract;
  }

  /** 单个 Tool 的 Schema：类型结构 + 描述 + 权限（供 Discovery 与 Python 校验共用） */
  public static Map<String, Object> exportTool(AgentToolName tool) {
    Map<String, Object> schema = schemaOf(tool.getRequestType(), 0);
    Map<String, Object> described = new LinkedHashMap<>();
    described.put("description", tool.getDescription());
    described.put("permission", tool.getPermission().name());
    described.putAll(schema);
    return described;
  }

  /** 某个请求模型的参数名集合（用于「未知参数」拒绝，与 Schema 同源） */
  public static List<String> parameterNames(Class<?> requestType) {
    return Arrays.stream(requestType.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
  }

  /**
   * record → JSON Schema 片段。
   *
   * <p>约定：带 {@code @NotNull/@NotBlank/@NotEmpty} 的字段进 required；
   * 约束按 jakarta validation 注解映射；字段语义说明取自 {@link ToolParam}。
   */
  static Map<String, Object> schemaOf(Class<?> type, int depth) {
    if (depth > MAX_DEPTH) {
      return objectSchema();
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (RecordComponent component : type.getRecordComponents()) {
      properties.put(component.getName(), propertySchema(type, component, depth));
      if (isRequired(type, component)) {
        required.add(component.getName());
      }
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    schema.put("properties", properties);
    return schema;
  }

  /** 单个字段的 Schema：标量 / 数组 / 嵌套对象 + 约束 + 语义说明 */
  private static Map<String, Object> propertySchema(
      Class<?> owner, RecordComponent component, int depth) {
    Map<String, Object> schema = new LinkedHashMap<>(typeSchema(component.getGenericType(), depth));

    NotBlank notBlank = annotationOf(owner, component, NotBlank.class);
    if (notBlank != null) {
      // @NotBlank 语义上等价于「非空字符串」，用 minLength 表达
      schema.put("minLength", 1);
    }
    if (annotationOf(owner, component, NotEmpty.class) != null) {
      // @NotEmpty 用在集合上：表达「至少一个元素」，否则调用方会以为空数组也能通过
      schema.put("minItems", 1);
    }
    Min min = annotationOf(owner, component, Min.class);
    if (min != null) {
      schema.put("minimum", min.value());
    }
    Max max = annotationOf(owner, component, Max.class);
    if (max != null) {
      schema.put("maximum", max.value());
    }
    if (annotationOf(owner, component, Positive.class) != null) {
      // 正数：用 exclusiveMinimum 表达，避免与 minimum 的包含语义混淆
      schema.put("exclusiveMinimum", 0);
    }
    ToolParam param = annotationOf(owner, component, ToolParam.class);
    if (param != null) {
      schema.put("description", param.value());
    }
    return schema;
  }

  /** Java 类型 → JSON Schema 类型片段 */
  private static Map<String, Object> typeSchema(Type genericType, int depth) {
    if (genericType instanceof ParameterizedType parameterized) {
      Type raw = parameterized.getRawType();
      if (raw == List.class) {
        Type elementType = parameterized.getActualTypeArguments()[0];
        return arraySchema(typeSchema(elementType, depth + 1));
      }
      return objectSchema();
    }

    if (genericType instanceof Class<?> clazz) {
      if (clazz == String.class || clazz == Character.class) {
        return scalarSchema("string", null);
      }
      if (clazz == Integer.class || clazz == int.class) {
        return scalarSchema("integer", "int32");
      }
      if (clazz == Long.class || clazz == long.class) {
        return scalarSchema("integer", "int64");
      }
      if (clazz == Boolean.class || clazz == boolean.class) {
        return scalarSchema("boolean", null);
      }
      if (clazz.isEnum()) {
        return enumSchema(
            Arrays.stream(clazz.getEnumConstants()).map(Object::toString).toList());
      }
      if (clazz.isRecord()) {
        return schemaOf(clazz, depth + 1);
      }
    }
    // 兜底：未知类型按对象处理，保证导出不失败（校验侧只做宽松约束）
    return objectSchema();
  }

  /*
   * 下面的构造全部用 LinkedHashMap 显式保持字段顺序。
   *
   * 刻意不用 Map.of：它的迭代顺序不保证（每次 JVM 启动可能不同），
   * 会让导出的 Schema 时快时慢地"变化"，golden 漂移检查随之随机失败。
   */

  private static Map<String, Object> scalarSchema(String type, String format) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", type);
    if (format != null) {
      schema.put("format", format);
    }
    return schema;
  }

  private static Map<String, Object> arraySchema(Map<String, Object> items) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("items", items);
    return schema;
  }

  private static Map<String, Object> enumSchema(List<String> values) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    schema.put("enum", values);
    return schema;
  }

  private static Map<String, Object> objectSchema() {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    return schema;
  }

  /** 必填判据：NotNull / NotBlank / NotEmpty 三者之一 */
  private static boolean isRequired(Class<?> owner, RecordComponent component) {
    return annotationOf(owner, component, NotNull.class) != null
        || annotationOf(owner, component, NotBlank.class) != null
        || annotationOf(owner, component, NotEmpty.class) != null;
  }

  /**
   * 读取 record component 上的注解。
   *
   * <p>jakarta validation 的注解会在 record component 与对应字段/参数之间传播，
   * 不同 JDK 版本下的可见位置不完全一致——两处都查一次，避免因传播规则差异漏读约束。
   */
  private static <A extends Annotation> A annotationOf(
      Class<?> owner, RecordComponent component, Class<A> type) {
    A direct = component.getAnnotation(type);
    if (direct != null) {
      return direct;
    }
    try {
      return owner.getDeclaredField(component.getName()).getAnnotation(type);
    } catch (NoSuchFieldException e) {
      return null;
    }
  }
}
