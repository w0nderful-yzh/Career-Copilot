package interview.guide.modules.agenttool.contract;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.agenttool.model.AgentToolName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent Tool 契约测试（ARCH-1：契约单一事实源）。
 *
 * <p>契约的事实源是 Java 类型化请求模型，本测试负责两件事：
 * <ol>
 *   <li><b>漂移检查</b>——现时导出的 Schema 必须与仓库内 golden 文件一致。不一致说明有人改了请求模型
 *       却没重新导出：Java 侧校验与 Tool Discovery 会立刻变新，而 Python 侧的调用前校验仍停在旧版本，
 *       这类漂移正是「调用方以为传了、服务端其实没用」的温床。</li>
 *   <li><b>结构断言</b>——required 判据、约束映射、数组元素类型，以及「未知参数拒绝」所用的键集合
 *       必须与 Schema 同源（两处口径一旦分叉，拒绝逻辑会误杀合法调用）。</li>
 * </ol>
 *
 * <p>重新生成 golden 文件（只在显式开关下写入，普通测试只读不写）：
 * <pre>
 * AGENT_TOOLS_SCHEMA_WRITE=true ./gradlew :app:test --tests "*AgentToolContractTest*"
 * </pre>
 */
class AgentToolContractTest {

  /** golden 文件位置：Gradle Test 的工作目录是 app/，故上一层是仓库根 */
  private static final Path GOLDEN_FILE = Path.of("..", "docs", "contracts", "agent-tools.json");

  /** 再生成开关：只在显式设置时写文件，避免普通测试产生副作用 */
  private static final String WRITE_ENV = "AGENT_TOOLS_SCHEMA_WRITE";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("导出的契约与仓库内 golden 文件一致（漂移检查）")
  void contractMatchesGoldenFile() throws IOException {
    String actual = render(AgentToolSchemaExporter.exportContract());

    if ("true".equalsIgnoreCase(System.getenv(WRITE_ENV))) {
      Files.createDirectories(GOLDEN_FILE.getParent());
      Files.writeString(GOLDEN_FILE, actual, StandardCharsets.UTF_8);
      return;
    }

    assertThat(GOLDEN_FILE)
        .as("缺少契约 golden 文件；用 AGENT_TOOLS_SCHEMA_WRITE=true 运行本测试可生成")
        .exists();
    assertThat(actual.strip())
        .as("Agent Tool 契约已漂移。改动请求模型后请重新导出："
            + "AGENT_TOOLS_SCHEMA_WRITE=true ./gradlew :app:test --tests \"*AgentToolContractTest*\"，"
            + "并同步 Python 侧副本（见 AgentToolContractTest 的 Python 副本说明）")
        .isEqualTo(Files.readString(GOLDEN_FILE, StandardCharsets.UTF_8).strip());
  }

  @Test
  @DisplayName("每个 Tool 的 Schema 都拒绝未声明参数，并带描述与权限")
  void everyToolSchemaIsClosedAndDocumented() {
    Map<String, Object> contract = AgentToolSchemaExporter.exportContract();
    Map<?, ?> tools = (Map<?, ?>) contract.get("tools");

    assertThat(tools).hasSize(AgentToolName.values().length);
    assertThat(contract.get("version")).isEqualTo(AgentToolSchemaExporter.CONTRACT_VERSION);
    assertThat(contract.get("source")).isEqualTo(AgentToolSchemaExporter.SOURCE_FILE);

    for (AgentToolName tool : AgentToolName.values()) {
      Map<?, ?> schema = (Map<?, ?>) tools.get(tool.getName());
      assertThat(schema).as("缺少 Tool Schema: %s", tool.getName()).isNotNull();
      assertThat(schema.get("type")).isEqualTo("object");
      assertThat(schema.get("additionalProperties"))
          .as("必须关闭额外参数，否则「未知参数」到了服务端就是静默忽略")
          .isEqualTo(false);
      assertThat(schema.get("description")).isEqualTo(tool.getDescription());
      assertThat(schema.get("permission")).isEqualTo(tool.getPermission().name());
      assertThat(schema.get("properties")).isInstanceOf(Map.class);
    }
  }

  @Test
  @DisplayName("required 由 NotNull / NotBlank / NotEmpty 推导，可选项不进 required")
  void requiredDerivedFromValidationAnnotations() {
    assertThat(requiredOf("get_resume_version")).containsExactly("resumeId");
    assertThat(requiredOf("get_resume_analysis")).containsExactly("resumeId");
    assertThat(requiredOf("get_resume")).containsExactly("resumeId");
    assertThat(requiredOf("get_job")).containsExactly("jobId");
    assertThat(requiredOf("get_interview_report")).containsExactly("sessionId");
    assertThat(requiredOf("apply_resume_patches")).containsExactly("proposalId");
    assertThat(requiredOf("search_knowledge")).containsExactly("knowledgeBaseIds", "question");
    // 创建面试：只有方向是必填；难度/题数/resumeId 等都可缺省（有服务端默认值）
    assertThat(requiredOf("create_interview")).containsExactly("skillId");
    // 无参数 Tool：空 required、空 properties
    assertThat(requiredOf("get_resume_list")).isEmpty();
    assertThat(propertiesOf("get_skill_profile")).isEmpty();
  }

  @Test
  @DisplayName("jakarta validation 约束映射进 Schema（范围与正数）")
  void constraintsMappedFromValidationAnnotations() {
    Map<?, ?> questionCount = (Map<?, ?>) propertiesOf("create_interview").get("questionCount");
    assertThat(questionCount.get("type")).isEqualTo("integer");
    // @Min/@Max 的 value 是 long，导出结果同为长整型（JSON 表示都是数字）
    assertThat(questionCount.get("minimum")).isEqualTo(3L);
    assertThat(questionCount.get("maximum")).isEqualTo(20L);

    Map<?, ?> resumeId = (Map<?, ?>) propertiesOf("create_interview").get("resumeId");
    assertThat(resumeId.get("exclusiveMinimum")).isEqualTo(0);

    Map<?, ?> question = (Map<?, ?>) propertiesOf("search_knowledge").get("question");
    assertThat(question.get("minLength")).isEqualTo(1);
  }

  @Test
  @DisplayName("数组元素类型进 items（Long 列表导出为 int64）")
  @SuppressWarnings("unchecked")
  void arrayItemsCarryElementType() {
    Map<String, Object> knowledgeBaseIds =
        (Map<String, Object>) propertiesOf("search_knowledge").get("knowledgeBaseIds");
    assertThat(knowledgeBaseIds.get("type")).isEqualTo("array");
    Map<String, Object> idItems = (Map<String, Object>) knowledgeBaseIds.get("items");
    assertThat(idItems.get("type")).isEqualTo("integer");
    assertThat(idItems.get("format")).isEqualTo("int64");

    Map<String, Object> focusCategories =
        (Map<String, Object>) propertiesOf("create_interview").get("focusCategories");
    Map<String, Object> focusItems = (Map<String, Object>) focusCategories.get("items");
    assertThat(focusItems.get("type")).isEqualTo("string");
  }

  @Test
  @DisplayName("「未知参数拒绝」用的键集合与 Schema properties 同源")
  void parameterNamesMatchSchemaProperties() {
    for (AgentToolName tool : AgentToolName.values()) {
      List<String> declared = AgentToolSchemaExporter.parameterNames(tool.getRequestType());
      assertThat(declared)
          .as("Tool %s 的参数名与 Schema 属性不一致会让校验误杀合法调用", tool.getName())
          .containsExactlyElementsOf(propertiesOf(tool.getName()).keySet().stream()
              .map(Object::toString)
              .toList());
    }
  }

  /** 读取某 Tool 的 required 列表（无 required 时为空） */
  @SuppressWarnings("unchecked")
  private static List<String> requiredOf(String toolName) {
    Map<String, Object> schema = toolSchema(toolName);
    List<String> required = (List<String>) schema.get("required");
    return required == null ? List.of() : required;
  }

  /** 读取某 Tool 的 properties */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> propertiesOf(String toolName) {
    return (Map<String, Object>) toolSchema(toolName).get("properties");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toolSchema(String toolName) {
    Map<String, Object> contract = AgentToolSchemaExporter.exportContract();
    Map<String, Object> tools = (Map<String, Object>) contract.get("tools");
    return (Map<String, Object>) tools.get(toolName);
  }

  /** 统一渲染方式，保证 golden 文件的格式稳定可 diff */
  private static String render(Map<String, Object> contract) {
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(contract) + "\n";
  }
}
