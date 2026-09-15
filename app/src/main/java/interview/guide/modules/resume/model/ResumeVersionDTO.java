package interview.guide.modules.resume.model;

import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 简历版本 DTO（P2-0）：对外暴露的版本信息。
 *
 * <p>contentJson 已反序列化为结构化对象（前端直接消费，无需二次解析）；
 * optimizationType/targetJobId/targetDirection 透出优化坐标系（P2 待修正：
 * 让「通用 / 定向方向 / JD 定向」在版本列表上可分辨，而不是一律显示为通用）。
 */
public record ResumeVersionDTO(
    Long id,
    Long resumeId,
    int version,
    String source,
    String confirmationStatus,
    String optimizationType,
    Long targetJobId,
    String targetDirection,
    ResumeContentJson content,
    List<String> missingFields,
    LocalDateTime sourceCreatedAt,
    LocalDateTime createdAt
) {

  public static ResumeVersionDTO from(ResumeVersionEntity entity, ObjectMapper objectMapper) {
    ResumeContentJson content = parseContent(entity.getContentJson(), objectMapper);
    return new ResumeVersionDTO(
        entity.getId(),
        entity.getResumeId(),
        entity.getVersion(),
        entity.getSource() != null ? entity.getSource().name() : null,
        entity.getConfirmationStatus() != null ? entity.getConfirmationStatus().name() : null,
        entity.getOptimizationType(),
        entity.getTargetJobId(),
        entity.getTargetDirection(),
        content,
        parseMissingFields(entity.getMissingFieldsJson(), objectMapper),
        entity.getSourceCreatedAt(),
        entity.getCreatedAt());
  }

  private static ResumeContentJson parseContent(String json, ObjectMapper objectMapper) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, ResumeContentJson.class);
    } catch (JacksonException e) {
      // 单版本内容损坏不应拖垮列表查询：返回空内容由上层提示
      return null;
    }
  }

  private static List<String> parseMissingFields(String json, ObjectMapper objectMapper) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (JacksonException e) {
      return List.of();
    }
  }
}
