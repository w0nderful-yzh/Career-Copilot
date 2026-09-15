import { useState, type ReactNode } from 'react';
import {
  AlertTriangle,
  Briefcase,
  FileText,
  GraduationCap,
  Layers,
  Plus,
  Save,
  Trash2,
  User,
  Wrench,
  X,
} from 'lucide-react';
import type {
  ResumeContentJson,
  ResumeCustomSection,
  ResumeEducationItem,
  ResumeExperienceItem,
  ResumeProjectItem,
  ResumeSkillItem,
} from '../api/history';
import {
  EMPTY_CUSTOM_SECTION,
  EMPTY_EDUCATION,
  EMPTY_EXPERIENCE,
  EMPTY_PROJECT,
  EMPTY_SKILL,
  appendListItem,
  bulletsToText,
  createEmptyContent,
  hasCriticalGap,
  isMissingField,
  removeListItem,
  textToBullets,
  updateBasicInfo,
  updateListItem,
} from '../utils/resumeContentEdit';

// 简历解析纠错/补录（P2 待修正）：
// 解析结果错一处则后续 Patch / Preview / 导出全错，因此在「确认」这一步必须能改。
// 表单直接编辑确认请求携带的 correctedContent，后端 confirmVersion 以修正版为准。

/** 输入框：缺失字段用红框高亮，引导用户优先补录 */
function Field({
  label,
  value,
  onChange,
  missing = false,
  placeholder,
  className = '',
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  missing?: boolean;
  placeholder?: string;
  className?: string;
}) {
  return (
    <label className={`block ${className}`}>
      <span
        className={`mb-1 flex items-center gap-1 text-[11px] font-medium ${
          missing ? 'text-red-500' : 'text-slate-400 dark:text-slate-500'
        }`}
      >
        {missing && <AlertTriangle className="h-3 w-3" />}
        {label}
        {missing && <span className="font-normal">（未解析到，请补录）</span>}
      </span>
      <input
        type="text"
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        className={`w-full rounded-lg border px-2.5 py-1.5 text-xs text-slate-700 outline-none transition focus:border-primary-400 focus:ring-1 focus:ring-primary-200 dark:bg-slate-800 dark:text-slate-200 ${
          missing
            ? 'border-red-300 bg-red-50/50 dark:border-red-800/60 dark:bg-red-950/20'
            : 'border-slate-200 dark:border-slate-700'
        }`}
      />
    </label>
  );
}

/** 多行输入：bullets / 自定义段条目按「一行一条」编辑 */
function TextAreaField({
  label,
  value,
  onChange,
  rows = 3,
  placeholder,
  missing = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  placeholder?: string;
  missing?: boolean;
}) {
  return (
    <label className="block">
      <span
        className={`mb-1 flex items-center gap-1 text-[11px] font-medium ${
          missing ? 'text-red-500' : 'text-slate-400 dark:text-slate-500'
        }`}
      >
        {missing && <AlertTriangle className="h-3 w-3" />}
        {label}
      </span>
      <textarea
        rows={rows}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        className={`w-full resize-y rounded-lg border px-2.5 py-1.5 text-xs leading-5 text-slate-700 outline-none transition focus:border-primary-400 focus:ring-1 focus:ring-primary-200 dark:bg-slate-800 dark:text-slate-200 ${
          missing
            ? 'border-red-300 bg-red-50/50 dark:border-red-800/60 dark:bg-red-950/20'
            : 'border-slate-200 dark:border-slate-700'
        }`}
      />
      <span className="mt-0.5 block text-[10px] text-slate-400">每行一条</span>
    </label>
  );
}

/** 段落容器：标题 + 缺失提示 + 新增按钮 / 删除按钮 */
function SectionShell({
  icon: Icon,
  title,
  missing,
  count,
  onAdd,
  onRemove,
  children,
}: {
  icon: typeof User;
  title: string;
  missing?: boolean;
  count?: number;
  onAdd?: () => void;
  onRemove?: () => void;
  children: ReactNode;
}) {
  return (
    <div
      className={`rounded-xl border p-3 ${
        missing
          ? 'border-red-200 bg-red-50/40 dark:border-red-800/50 dark:bg-red-950/10'
          : 'border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-800/60'
      }`}
    >
      <div className="mb-2 flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 dark:text-slate-300">
          <Icon className="h-3.5 w-3.5" />
          {title}
          {typeof count === 'number' && (
            <span className="font-normal text-slate-400">（{count}）</span>
          )}
          {missing && (
            <span className="flex items-center gap-1 font-normal text-red-500">
              <AlertTriangle className="h-3 w-3" />
              未解析到，建议补录
            </span>
          )}
        </span>
        <div className="flex items-center gap-2">
          {onAdd && (
            <button
              type="button"
              onClick={onAdd}
              className="flex items-center gap-0.5 text-[11px] font-medium text-primary-600 hover:underline dark:text-primary-300"
            >
              <Plus className="h-3 w-3" />
              添加
            </button>
          )}
          {onRemove && (
            <button
              type="button"
              onClick={onRemove}
              className="flex items-center gap-0.5 text-[11px] font-medium text-red-500 hover:underline"
            >
              <Trash2 className="h-3 w-3" />
              删除本条
            </button>
          )}
        </div>
      </div>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

export default function ResumeContentEditor({
  initialContent,
  missingFields,
  saving,
  onCancel,
  onConfirm,
}: {
  initialContent: ResumeContentJson | null;
  missingFields: string[];
  saving: boolean;
  onCancel: () => void;
  onConfirm: (content: ResumeContentJson) => void;
}) {
  const [content, setContent] = useState<ResumeContentJson>(
    () => initialContent ?? createEmptyContent(),
  );
  const editing = content;
  const basic = content.basicInfo ?? {};

  const setEducation = (items: ResumeEducationItem[]) =>
    setContent((prev) => ({ ...prev, education: items }));
  const setExperience = (items: ResumeExperienceItem[]) =>
    setContent((prev) => ({ ...prev, experience: items }));
  const setProjects = (items: ResumeProjectItem[]) =>
    setContent((prev) => ({ ...prev, projects: items }));
  const setSkills = (items: ResumeSkillItem[]) =>
    setContent((prev) => ({ ...prev, skills: items }));
  const setCustomSections = (items: ResumeCustomSection[]) =>
    setContent((prev) => ({ ...prev, customSections: items }));

  const criticalGap = hasCriticalGap(content);

  return (
    <div className="mt-3 space-y-3 rounded-xl border border-primary-200 bg-white/80 p-3 dark:border-primary-800/50 dark:bg-slate-900/40">
      <div className="flex items-center justify-between">
        <p className="text-xs font-semibold text-slate-600 dark:text-slate-300">
          修正解析结果（保存后即作为后续优化的依据）
        </p>
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          className="flex items-center gap-1 text-[11px] text-slate-400 hover:text-slate-600 disabled:opacity-50 dark:hover:text-slate-200"
        >
          <X className="h-3 w-3" />
          取消
        </button>
      </div>

      {/* 基本信息 */}
      <SectionShell icon={User} title="基本信息">
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <Field
            label="姓名"
            value={basic.name ?? ''}
            missing={isMissingField(missingFields, 'basicInfo.name')}
            onChange={(value) =>
              setContent((prev) => updateBasicInfo(prev, { name: value }))
            }
          />
          <Field
            label="电话"
            value={basic.phone ?? ''}
            missing={isMissingField(missingFields, 'basicInfo.contact')}
            onChange={(value) =>
              setContent((prev) => updateBasicInfo(prev, { phone: value }))
            }
          />
          <Field
            label="邮箱"
            value={basic.email ?? ''}
            missing={isMissingField(missingFields, 'basicInfo.contact')}
            onChange={(value) =>
              setContent((prev) => updateBasicInfo(prev, { email: value }))
            }
          />
          <Field
            label="所在地"
            value={basic.location ?? ''}
            onChange={(value) =>
              setContent((prev) => updateBasicInfo(prev, { location: value }))
            }
          />
          <Field
            label="求职意向"
            value={basic.jobIntention ?? ''}
            onChange={(value) =>
              setContent((prev) => updateBasicInfo(prev, { jobIntention: value }))
            }
            className="sm:col-span-2"
          />
        </div>
      </SectionShell>

      {/* 教育经历 */}
      <SectionShell
        icon={GraduationCap}
        title="教育经历"
        count={editing.education?.length ?? 0}
        missing={isMissingField(missingFields, 'education')}
        onAdd={() =>
          setEducation(appendListItem(editing.education, { ...EMPTY_EDUCATION }))
        }
      >
        {(editing.education ?? []).length === 0 && (
          <p className="text-[11px] text-slate-400">暂无教育经历，可点击「添加」补录。</p>
        )}
        {(editing.education ?? []).map((edu, index) => (
          <SectionShell
            key={index}
            icon={GraduationCap}
            title={`教育经历 ${index + 1}`}
            onRemove={() => setEducation(removeListItem(editing.education, index))}
          >
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
              <Field
                label="学校"
                value={edu.school ?? ''}
                onChange={(value) => setEducation(updateListItem(editing.education, index, { school: value }))}
              />
              <Field
                label="专业"
                value={edu.major ?? ''}
                onChange={(value) => setEducation(updateListItem(editing.education, index, { major: value }))}
              />
              <Field
                label="学历"
                value={edu.degree ?? ''}
                placeholder="本科 / 硕士"
                onChange={(value) => setEducation(updateListItem(editing.education, index, { degree: value }))}
              />
              <Field
                label="开始时间"
                value={edu.startDate ?? ''}
                placeholder="2021-09"
                onChange={(value) => setEducation(updateListItem(editing.education, index, { startDate: value }))}
              />
              <Field
                label="结束时间"
                value={edu.endDate ?? ''}
                placeholder="2025-06"
                onChange={(value) => setEducation(updateListItem(editing.education, index, { endDate: value }))}
              />
              <Field
                label="补充说明"
                value={edu.description ?? ''}
                placeholder="GPA / 排名 / 主修课程"
                onChange={(value) => setEducation(updateListItem(editing.education, index, { description: value }))}
              />
            </div>
          </SectionShell>
        ))}
      </SectionShell>

      {/* 工作/实习经历 */}
      <SectionShell
        icon={Briefcase}
        title="工作/实习经历"
        count={editing.experience?.length ?? 0}
        missing={isMissingField(missingFields, 'experience')}
        onAdd={() =>
          setExperience(appendListItem(editing.experience, { ...EMPTY_EXPERIENCE }))
        }
      >
        {(editing.experience ?? []).length === 0 && (
          <p className="text-[11px] text-slate-400">暂无工作/实习经历，可点击「添加」补录。</p>
        )}
        {(editing.experience ?? []).map((exp, index) => (
          <SectionShell
            key={index}
            icon={Briefcase}
            title={`经历 ${index + 1}`}
            onRemove={() => setExperience(removeListItem(editing.experience, index))}
          >
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <Field
                label="公司"
                value={exp.company ?? ''}
                onChange={(value) => setExperience(updateListItem(editing.experience, index, { company: value }))}
              />
              <Field
                label="岗位"
                value={exp.position ?? ''}
                onChange={(value) => setExperience(updateListItem(editing.experience, index, { position: value }))}
              />
              <Field
                label="开始时间"
                value={exp.startDate ?? ''}
                placeholder="2024-07"
                onChange={(value) => setExperience(updateListItem(editing.experience, index, { startDate: value }))}
              />
              <Field
                label="结束时间"
                value={exp.endDate ?? ''}
                placeholder="2024-12 / 至今"
                onChange={(value) => setExperience(updateListItem(editing.experience, index, { endDate: value }))}
              />
            </div>
            <TextAreaField
              label="工作内容"
              rows={3}
              value={bulletsToText(exp.bullets)}
              onChange={(value) => setExperience(updateListItem(editing.experience, index, { bullets: textToBullets(value) }))}
            />
          </SectionShell>
        ))}
      </SectionShell>

      {/* 项目经历 */}
      <SectionShell
        icon={Layers}
        title="项目经历"
        count={editing.projects?.length ?? 0}
        missing={isMissingField(missingFields, 'projects')}
        onAdd={() => setProjects(appendListItem(editing.projects, { ...EMPTY_PROJECT }))}
      >
        {(editing.projects ?? []).length === 0 && (
          <p className="text-[11px] text-slate-400">暂无项目经历，可点击「添加」补录。</p>
        )}
        {(editing.projects ?? []).map((project, index) => (
          <SectionShell
            key={index}
            icon={Layers}
            title={`项目 ${index + 1}`}
            onRemove={() => setProjects(removeListItem(editing.projects, index))}
          >
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <Field
                label="项目名称"
                value={project.name ?? ''}
                onChange={(value) => setProjects(updateListItem(editing.projects, index, { name: value }))}
              />
              <Field
                label="担任角色"
                value={project.role ?? ''}
                onChange={(value) => setProjects(updateListItem(editing.projects, index, { role: value }))}
              />
              <Field
                label="技术栈"
                value={project.techStack ?? ''}
                placeholder="Spring Boot, MySQL"
                onChange={(value) => setProjects(updateListItem(editing.projects, index, { techStack: value }))}
              />
              <Field
                label="开始时间"
                value={project.startDate ?? ''}
                placeholder="2024-03"
                onChange={(value) => setProjects(updateListItem(editing.projects, index, { startDate: value }))}
              />
              <Field
                label="结束时间"
                value={project.endDate ?? ''}
                placeholder="2024-06"
                onChange={(value) => setProjects(updateListItem(editing.projects, index, { endDate: value }))}
              />
            </div>
            <TextAreaField
              label="项目描述"
              rows={4}
              value={bulletsToText(project.bullets)}
              onChange={(value) => setProjects(updateListItem(editing.projects, index, { bullets: textToBullets(value) }))}
            />
          </SectionShell>
        ))}
      </SectionShell>

      {/* 技能 */}
      <SectionShell
        icon={Wrench}
        title="技能"
        count={editing.skills?.length ?? 0}
        onAdd={() => setSkills(appendListItem(editing.skills, { ...EMPTY_SKILL }))}
      >
        {(editing.skills ?? []).length === 0 && (
          <p className="text-[11px] text-slate-400">暂无技能条目，可点击「添加」补录。</p>
        )}
        {(editing.skills ?? []).map((skill, index) => (
          <SectionShell
            key={index}
            icon={Wrench}
            title={`技能 ${index + 1}`}
            onRemove={() => setSkills(removeListItem(editing.skills, index))}
          >
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
              <Field
                label="分类"
                value={skill.category ?? ''}
                placeholder="语言 / 框架 / 工具"
                onChange={(value) => setSkills(updateListItem(editing.skills, index, { category: value }))}
              />
              <Field
                label="内容"
                value={skill.content ?? ''}
                placeholder="熟悉 Java、Spring Boot"
                onChange={(value) => setSkills(updateListItem(editing.skills, index, { content: value }))}
                className="sm:col-span-2"
              />
            </div>
          </SectionShell>
        ))}
      </SectionShell>

      {/* 其他段落（证书/奖项/链接等兜底段） */}
      <SectionShell
        icon={FileText}
        title="其他段落"
        count={editing.customSections?.length ?? 0}
        onAdd={() =>
          setCustomSections(appendListItem(editing.customSections, { ...EMPTY_CUSTOM_SECTION }))
        }
      >
        {(editing.customSections ?? []).length === 0 && (
          <p className="text-[11px] text-slate-400">
            证书 / 奖项 / 作品链接等非标准段落会保留在这里，避免解析时静默丢失。
          </p>
        )}
        {(editing.customSections ?? []).map((section, index) => (
          <SectionShell
            key={index}
            icon={FileText}
            title={`段落 ${index + 1}`}
            onRemove={() => setCustomSections(removeListItem(editing.customSections, index))}
          >
            <Field
              label="段落标题"
              value={section.title ?? ''}
              placeholder="荣誉奖项"
              onChange={(value) => setCustomSections(updateListItem(editing.customSections, index, { title: value }))}
            />
            <TextAreaField
              label="段落内容"
              rows={3}
              value={bulletsToText(section.items)}
              onChange={(value) => setCustomSections(updateListItem(editing.customSections, index, { items: textToBullets(value) }))}
            />
          </SectionShell>
        ))}
      </SectionShell>

      {criticalGap && (
        <p className="flex items-start gap-1.5 rounded-lg bg-amber-50 px-2.5 py-2 text-[11px] text-amber-600 dark:bg-amber-950/30 dark:text-amber-300">
          <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />
          姓名与联系方式（电话或邮箱）仍不完整。可以先保存，但补齐后导出的简历才完整。
        </p>
      )}

      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={saving}
          onClick={() => onConfirm(content)}
          className="inline-flex items-center gap-1.5 rounded-lg bg-primary-500 px-4 py-2 text-sm font-medium text-white shadow-sm transition hover:bg-primary-600 disabled:opacity-50"
        >
          <Save className="h-4 w-4" />
          {saving ? '保存中…' : '保存修正并确认'}
        </button>
        <span className="text-[11px] text-slate-400">
          确认后该版本才会作为优化与导出的依据
        </span>
      </div>
    </div>
  );
}
