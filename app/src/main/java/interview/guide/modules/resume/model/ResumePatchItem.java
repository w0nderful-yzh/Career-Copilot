package interview.guide.modules.resume.model;

/**
 * 单条优化建议（P2-1）：Agent 产出的 JSON-path 定位修改。
 *
 * <p>path 指向 ResumeContentJson 结构内的位置（如 "projects[0].bullets[1]"）；
 * oldValue 用于应用时一致性校验（版本内容与提案生成时不一致则拒绝应用）。
 * REORDER 一期 schema 保留但校验器拒绝（TodoList 已确认决策）。
 */
public record ResumePatchItem(
    String id,
    PatchType type,
    String path,
    String oldValue,
    String newValue,
    String reason
) {

  public enum PatchType {
    REPLACE,  // 修改现有内容
    ADD,      // 新增条目（bullet / 项目 / 技能）
    DELETE,   // 删除冗余内容
    REORDER   // 调整顺序（一期校验器直接拒绝）
  }
}
