import { useEffect, useMemo, useState } from 'react';
import { Check, Loader2 } from 'lucide-react';
import { skillApi, type CategoryDTO, type SkillDTO } from '../../api/skill';
import type { InterviewConfig } from '../../types/copilot';

// 内联面试配置面板（Interview Mode 重构）：
// 点「调整配置」在当前提案卡内展开，不触发 Agent、不发聊天消息。
// 手动修改方向/难度/题量/focus → [应用并开始面试] → 本地合成 CREATE_INTERVIEW action。

const DIFFICULTY_OPTIONS = [
  { value: 'junior', label: '校招', desc: '0-1 年' },
  { value: 'mid', label: '中级', desc: '1-3 年' },
  { value: 'senior', label: '高级', desc: '3 年+' },
];

const QUESTION_COUNT_OPTIONS = [6, 8, 10];

export interface InterviewConfigPanelProps {
  /** 当前推荐配置（作为面板初值） */
  initial: InterviewConfig;
  onApply: (config: InterviewConfig) => void;
  onCancel: () => void;
  disabled?: boolean;
}

export default function InterviewConfigPanel({
  initial,
  onApply,
  onCancel,
  disabled,
}: InterviewConfigPanelProps) {
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [loadingSkills, setLoadingSkills] = useState(false);
  const [direction, setDirection] = useState(initial.direction);
  const [difficulty, setDifficulty] = useState(initial.difficulty);
  const [questionCount, setQuestionCount] = useState(initial.question_count);
  const [focus, setFocus] = useState<string[]>(initial.focus);

  useEffect(() => {
    let cancelled = false;
    setLoadingSkills(true);
    skillApi
      .listSkills()
      .then((list) => {
        if (!cancelled) setSkills(list);
      })
      .catch(() => {
        // 方向列表加载失败：保留初值，面板仍可手动改难度/题量
      })
      .finally(() => {
        if (!cancelled) setLoadingSkills(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const selectedSkill = useMemo(
    () => skills.find((s) => s.id === direction),
    [skills, direction],
  );
  // focus 候选 = 当前方向 categories；方向尚未加载完成时用 initial.focus 兜底
  const categoryOptions: CategoryDTO[] = selectedSkill?.categories ?? [];
  const isCustom = direction === 'custom' || categoryOptions.length === 0;

  // 切换方向时，若原 focus 不属于新方向则清空（避免把 JVM 带到前端方向）
  const handleDirectionChange = (next: string) => {
    setDirection(next);
    const nextSkill = skills.find((s) => s.id === next);
    const validKeys = new Set((nextSkill?.categories ?? []).map((c) => c.key));
    if (focus.some((f) => !validKeys.has(f))) {
      setFocus([]);
    }
  };

  const toggleFocus = (key: string) => {
    setFocus((prev) => (prev.includes(key) ? prev.filter((f) => f !== key) : [...prev, key]));
  };

  const apply = () => {
    onApply({ direction, difficulty, question_count: questionCount, focus });
  };

  return (
    <div className="mt-3 rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-600 dark:bg-slate-800/70">
      <p className="mb-2 text-xs font-bold text-slate-500 dark:text-slate-400">调整面试配置</p>

      {/* 方向 */}
      <label className="mb-1 block text-xs text-slate-400">面试方向</label>
      <div className="mb-3 flex flex-wrap gap-1.5">
        {skills.map((skill) => (
          <button
            key={skill.id}
            type="button"
            disabled={disabled || loadingSkills}
            onClick={() => handleDirectionChange(skill.id)}
            className={`rounded-lg px-2.5 py-1 text-xs font-medium transition ${
              direction === skill.id
                ? 'bg-primary-600 text-white'
                : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600'
            }`}
          >
            {skill.name || skill.id}
          </button>
        ))}
        {loadingSkills && <Loader2 className="h-3.5 w-3.5 animate-spin text-slate-400" />}
      </div>

      {/* 难度 */}
      <label className="mb-1 block text-xs text-slate-400">难度</label>
      <div className="mb-3 flex flex-wrap gap-1.5">
        {DIFFICULTY_OPTIONS.map((opt) => (
          <button
            key={opt.value}
            type="button"
            disabled={disabled}
            onClick={() => setDifficulty(opt.value)}
            className={`rounded-lg px-2.5 py-1 text-xs font-medium transition ${
              difficulty === opt.value
                ? 'bg-primary-600 text-white'
                : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600'
            }`}
          >
            {opt.label}
            <span className="ml-1 opacity-70">{opt.desc}</span>
          </button>
        ))}
      </div>

      {/* 题量 */}
      <label className="mb-1 block text-xs text-slate-400">题目数量</label>
      <div className="mb-3 flex flex-wrap gap-1.5">
        {QUESTION_COUNT_OPTIONS.map((count) => (
          <button
            key={count}
            type="button"
            disabled={disabled}
            onClick={() => setQuestionCount(count)}
            className={`rounded-lg px-3 py-1 text-xs font-medium transition ${
              questionCount === count
                ? 'bg-primary-600 text-white'
                : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600'
            }`}
          >
            {count} 题
          </button>
        ))}
      </div>

      {/* 重点 focus（categories 多选；自定义方向无 categories 时隐藏） */}
      {!isCustom && categoryOptions.length > 0 && (
        <>
          <label className="mb-1 block text-xs text-slate-400">
            重点考察 <span className="text-slate-300">（可多选，留空 = 综合）</span>
          </label>
          <div className="flex flex-wrap gap-1.5">
            {categoryOptions.map((cat) => {
              const active = focus.includes(cat.key);
              return (
                <button
                  key={cat.key}
                  type="button"
                  disabled={disabled}
                  onClick={() => toggleFocus(cat.key)}
                  className={`inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-medium transition ${
                    active
                      ? 'bg-indigo-600 text-white'
                      : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600'
                  }`}
                >
                  {active && <Check className="h-3 w-3" />}
                  {cat.label}
                </button>
              );
            })}
          </div>
        </>
      )}

      <div className="mt-4 flex justify-end gap-2">
        <button
          type="button"
          disabled={disabled}
          onClick={onCancel}
          className="rounded-lg px-3 py-1.5 text-xs font-semibold text-slate-500 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700"
        >
          取消
        </button>
        <button
          type="button"
          disabled={disabled || !direction}
          onClick={apply}
          className="rounded-lg bg-primary-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-primary-700 disabled:opacity-50"
        >
          应用并开始面试
        </button>
      </div>
    </div>
  );
}
