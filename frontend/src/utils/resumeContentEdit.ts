// 简历结构化内容的编辑操作（纯函数）。
//
// 抽成独立模块的原因：解析纠错/补录表单的全部状态变更都是「不可变数组/对象操作」，
// 直接写在组件里无法单测（没有 jsdom 环境），而错删/错改一条经历会污染后续
// Patch 与导出结果——这是必须被单测固化的语义。

import type {
  ResumeContentJson,
  ResumeCustomSection,
  ResumeEducationItem,
  ResumeExperienceItem,
  ResumeProjectItem,
  ResumeSkillItem,
} from '../api/history';

/** 空结构化内容：补录从零开始时用 */
export function createEmptyContent(): ResumeContentJson {
  return {
    basicInfo: { name: '', phone: '', email: '', location: '', jobIntention: '' },
    education: [],
    experience: [],
    projects: [],
    skills: [],
    customSections: [],
  };
}

export const EMPTY_EDUCATION: ResumeEducationItem = {
  school: '', major: '', degree: '', startDate: '', endDate: '', description: '',
};

export const EMPTY_EXPERIENCE: ResumeExperienceItem = {
  company: '', position: '', startDate: '', endDate: '', bullets: [],
};

export const EMPTY_PROJECT: ResumeProjectItem = {
  name: '', role: '', startDate: '', endDate: '', techStack: '', bullets: [],
};

export const EMPTY_SKILL: ResumeSkillItem = { category: '', content: '' };

export const EMPTY_CUSTOM_SECTION: ResumeCustomSection = { title: '', items: [] };

/** 更新基本信息（浅合并，未提交的字段保持原值） */
export function updateBasicInfo(
  content: ResumeContentJson,
  patch: NonNullable<ResumeContentJson['basicInfo']>,
): ResumeContentJson {
  return { ...content, basicInfo: { ...(content.basicInfo ?? {}), ...patch } };
}

/** 更新列表中的某一项（不可变；越界索引原样返回） */
export function updateListItem<T>(
  items: T[] | null | undefined,
  index: number,
  patch: Partial<T>,
): T[] {
  return (items ?? []).map((item, i) => (i === index ? { ...item, ...patch } : item));
}

/** 追加一项（null/undefined 视为空列表） */
export function appendListItem<T>(items: T[] | null | undefined, item: T): T[] {
  return [...(items ?? []), item];
}

/** 删除一项（不可变） */
export function removeListItem<T>(items: T[] | null | undefined, index: number): T[] {
  return (items ?? []).filter((_, i) => i !== index);
}

/** bullets → 多行文本（每行一条），供 textarea 编辑 */
export function bulletsToText(bullets: string[] | null | undefined): string {
  return (bullets ?? []).join('\n');
}

/** 多行文本 → bullets：去首尾空白、丢弃空行（避免确认后落库空条目） */
export function textToBullets(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
}

/**
 * 缺失字段判定（后端 missingFields 为 path 前缀）。
 *
 * 后端既会给出精确 path（basicInfo.name），也会给出组合项（experience/projects
 * 表示两者皆空），故按 `/` 拆段后逐段匹配「等值 或 子字段前缀」。
 */
export function isMissingField(
  missingFields: string[] | null | undefined,
  key: string,
): boolean {
  return (missingFields ?? []).some((field) =>
    field
      .split('/')
      .some((segment) => segment === key || segment.startsWith(`${key}.`)),
  );
}

/** 补录是否仍然缺少关键字段（姓名 + 联系方式）：确认前给用户如实提示 */
export function hasCriticalGap(content: ResumeContentJson): boolean {
  const basic = content.basicInfo ?? {};
  const hasContact = Boolean((basic.phone ?? '').trim() || (basic.email ?? '').trim());
  return !(basic.name ?? '').trim() || !hasContact;
}
