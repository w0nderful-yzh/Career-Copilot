// 本场面试的画像变化：展示推导（纯函数）。
//
// 抽出来的原因与 interviewTurns 相同——「怎么呈现一次分数变化」有明确的产品语义
// （新增/涨/跌/平、哪条排前面、证据的来源怎么读），必须可被单测固化。

import type { ImpactEvidence, SkillImpact } from '../types/interview';

export type ImpactTone = 'up' | 'down' | 'flat' | 'new';

/** 该技能的变化色调；「本场新增」单独成一类（它没有 before，谈不上涨跌） */
export function impactTone(impact: SkillImpact): ImpactTone {
  if (impact.beforeScore === null) return 'new';
  if (impact.delta > 0) return 'up';
  if (impact.delta < 0) return 'down';
  return 'flat';
}

/** 变化幅度文本；首次考到的技能没有「涨了多少」可言 */
export function deltaText(impact: SkillImpact): string {
  if (impact.beforeScore === null) return '本场新增';
  return impact.delta > 0 ? `+${impact.delta}` : `${impact.delta}`;
}

/**
 * 展示排序：有变化的在前（按变化幅度降序），无变化的殿后。
 *
 * 变化幅度用绝对值——「跌得最狠」和「涨得最多」都值得先看，只按 delta 排
 * 会让「-20」排在一个无关紧要的「+1」后面。
 */
export function orderImpacts(skills: SkillImpact[]): SkillImpact[] {
  return [...skills].sort((a, b) => {
    const magnitudeA = a.beforeScore === null ? Infinity : Math.abs(a.delta);
    const magnitudeB = b.beforeScore === null ? Infinity : Math.abs(b.delta);
    if (magnitudeA !== magnitudeB) return magnitudeB - magnitudeA;
    return a.skill.localeCompare(b.skill, 'zh-Hans-CN');
  });
}

/**
 * 证据来源的可读文本："面试 · 第 3 题"；序号缺失时退化为场次标识。
 *
 * questionOrdinal 是**真实发生顺序**（1 起，P4-1），不再需要 +1；拿不到时宁可不显示序号，
 * 也不编一个可能指错题的假号。
 */
export function evidenceSourceText(evidence: ImpactEvidence): string {
  if (evidence.questionOrdinal === null || evidence.questionOrdinal === undefined) {
    return '面试 · 场次记录';
  }
  return `面试 · 第 ${evidence.questionOrdinal} 题`;
}

/** 证据时间："2026-09-15 10:00"；缺失或非法时返回空串（不显示占位假时间） */
export function evidenceTimeText(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (value: number) => value.toString().padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 从证据 sourceId（"sessionId:questionIndex"）解析出场次 ID，供追溯跳转 */
export function sessionIdOf(evidence: ImpactEvidence): string | null {
  const separator = evidence.sourceId.lastIndexOf(':');
  if (separator <= 0) return null;
  return evidence.sourceId.slice(0, separator);
}
