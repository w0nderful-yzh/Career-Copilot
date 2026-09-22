-- 画像证据支持「声明型证据」（P3 待收口）：简历来源接入 Evidence。
--
-- 背景：简历侧没有逐技能分可用——结构化解析的 skills 只有 category/content（技能名，
-- 无分），简历分析只有四个维度分（内容完整性/结构清晰度/技能匹配度/表达专业性/项目经验），
-- 都不是技能分。若给简历技能造一个分数，就违反了 Core-4「分必须能由证据还原」。
--
-- 因此 RESUME 来源以「声明」语义入表：score 为空表示「简历里列了这项技能，但尚无评分证据」。
--   * 不参与画像聚合（聚合只看有分证据）——画像分仍完全由可量化的面试证据决定；
--   * 单独通过画像查询返回（declaredSkills），供提案选 focus 与画像展示使用。
ALTER TABLE skill_evidence ALTER COLUMN score DROP NOT NULL;

COMMENT ON COLUMN skill_evidence.score IS
  '评分 (0-100)；NULL 表示声明型证据（简历列出的技能，尚无评分，不参与聚合）';
