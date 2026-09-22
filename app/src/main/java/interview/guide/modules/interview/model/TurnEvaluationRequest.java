package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 逐轮评估的输入（P4Q-1）。
 *
 * <p>除当前题与回答外，还要喂进「判断这份回答需要哪些参照物」：
 * 本场简历片段（他说的到底是不是简历里的经历）、最近相关问答（这轮有没有新信息）、
 * 画像基线（这次表现是否超出/低于长期水平）。
 *
 * <p>缺了参照物，模型只能孤立地看一道题——「沿用户亲历的事追问」「没有新增信息就转场」
 * 这类判断（P4Q-3 的验收）在上下文层面就无从谈起。
 *
 * <p>P4Q-2 批 2b 再补三类**决策边界**上下文：覆盖摘要（哪些话题已经问过、必要覆盖还差什么）、
 * 时间与追问预算（还剩多少答题时间、当前追问组还能问几条）、本轮合法候选
 * （Java 的策略实际可能选中的题，含稳定标识）。它们由实际轨迹与计划确定性推导，
 * 不是模型自述；缺失时调用方留空，由提示词给可读说明而不是编造数据。
 *
 * @param question        当前被回答的题（用 difficulty / expectedPoints / category）
 * @param answer          用户回答原文（已裁剪）
 * @param resumeSnippet   本场简历上下文片段（按当前题话题裁剪）；无简历依据时为 null
 * @param recentTurns     同技能最近若干轮问答（已格式化）；没有历史时为 null
 * @param profileBaseline 画像基线文本（如「MySQL 44 分（2 条证据）」）；无画像时为 null
 * @param coverageSummary 覆盖摘要（必要覆盖状态 / 已问话题 / 尚未问话题，多行文本）；无候选时为 null
 * @param budgetSummary   时间与追问预算摘要（多行文本）；没有任何预算数据时为 null
 * @param legalCandidates 本轮合法候选（已格式化为「[标识] 类型｜话题｜难度：题干」）；无候选时为空列表
 */
public record TurnEvaluationRequest(
    InterviewQuestionDTO question,
    String answer,
    String resumeSnippet,
    List<String> recentTurns,
    String profileBaseline,
    String coverageSummary,
    String budgetSummary,
    List<String> legalCandidates
) {

  public TurnEvaluationRequest {
    legalCandidates = legalCandidates == null ? List.of() : List.copyOf(legalCandidates);
  }

  /** 只有当前题与回答：历史数据修复等没有会话上下文的场景 */
  public static TurnEvaluationRequest of(InterviewQuestionDTO question, String answer) {
    return new TurnEvaluationRequest(question, answer, null, null, null, null, null, List.of());
  }
}
