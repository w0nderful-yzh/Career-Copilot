-- P5-1：JD Gap 与可应用 Patch 分开持久化，便于历史回放与审计。
ALTER TABLE resume_optimization_proposals
  ADD COLUMN jd_gap_analysis_json TEXT;
