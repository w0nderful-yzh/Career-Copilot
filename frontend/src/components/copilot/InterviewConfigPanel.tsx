import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { skillApi, type SkillDTO } from '../../api/skill';
import type { InterviewConfig } from '../../types/copilot';

// 内联面试配置面板（Interview Mode 重构）：
// 点「调整配置」在当前提案卡内展开，不触发 Agent、不发聊天消息。
// 手动修改方向/难度/时长/考察倾向 → [应用并开始面试] → 本地合成 CREATE_INTERVIEW action。

const DIFFICULTY_OPTIONS = [
  { value: 'junior', label: '校招', desc: '0-1 年' },
  { value: 'mid', label: '中级', desc: '1-3 年' },
  { value: 'senior', label: '高级', desc: '3 年+' },
];

const DURATION_OPTIONS = [15, 20, 30, 45];

export interface InterviewConfigPanelProps {
  /** 当前推荐配置（作为面板初值） */
  initial: InterviewConfig;
  weaknessFocus: string[];
  hasResume: boolean;
  onApply: (config: InterviewConfig) => void;
  onCancel: () => void;
  disabled?: boolean;
}

export default function InterviewConfigPanel({
  initial,
  weaknessFocus,
  hasResume,
  onApply,
  onCancel,
  disabled,
}: InterviewConfigPanelProps) {
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [loadingSkills, setLoadingSkills] = useState(false);
  const [direction, setDirection] = useState(initial.direction);
  const [difficulty, setDifficulty] = useState(initial.difficulty);
  const [plannedDurationMinutes, setPlannedDurationMinutes] = useState(
    initial.planned_duration_minutes,
  );
  const [emphasis, setEmphasis] = useState(initial.emphasis);

  useEffect(() => {
    let cancelled = false;
    setLoadingSkills(true);
    skillApi
      .listSkills()
      .then((list) => {
        if (!cancelled) setSkills(list);
      })
      .catch(() => {
        // 方向列表加载失败：保留初值，面板仍可手动改难度/时长
      })
      .finally(() => {
        if (!cancelled) setLoadingSkills(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleDirectionChange = (next: string) => {
    setDirection(next);
    // 画像薄弱项只对提案原方向有效，换方向后回到简历主导。
    if (next !== initial.direction) setEmphasis('RESUME');
  };

  const apply = () => {
    onApply({
      direction,
      difficulty,
      planned_duration_minutes: plannedDurationMinutes,
      required_topics: emphasis === initial.emphasis && direction === initial.direction
        ? initial.required_topics : [],
      focus: direction !== initial.direction ? []
        : emphasis === initial.emphasis ? initial.focus
        : emphasis === 'WEAKNESSES' ? weaknessFocus : [],
      emphasis,
    });
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

      {/* 预计时长：题数由覆盖、回答质量与预算动态产生 */}
      <label className="mb-1 block text-xs text-slate-400">预计时长</label>
      <div className="mb-3 flex flex-wrap gap-1.5">
        {DURATION_OPTIONS.map((minutes) => (
          <button
            key={minutes}
            type="button"
            disabled={disabled}
            onClick={() => setPlannedDurationMinutes(minutes)}
            className={`rounded-lg px-3 py-1 text-xs font-medium transition ${
              plannedDurationMinutes === minutes
                ? 'bg-primary-600 text-white'
                : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600'
            }`}
          >
            {minutes} 分钟
          </button>
        ))}
      </div>

      <label className="mb-1 block text-xs text-slate-400">考察倾向</label>
      <div className="mb-2 flex flex-wrap gap-1.5">
        {([
          ['RESUME', hasResume ? '围绕简历' : '综合面试', hasResume ? '从项目、实习与职责展开追问' : '未绑定简历，按岗位方向综合提问'],
          ['FUNDAMENTALS', '重点八股', '增加基础原理与常见知识题'],
          ['WEAKNESSES', '重点薄弱项', '参考有面试证据的低分技能'],
        ] as const).map(([value, label, description]) => (
          <button
            key={value}
            type="button"
            title={description}
            disabled={disabled || (value === 'WEAKNESSES' && (direction !== initial.direction || weaknessFocus.length === 0))}
            onClick={() => setEmphasis(value)}
            className={`rounded-lg px-2.5 py-1 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-40 ${
              emphasis === value
                ? 'bg-primary-600 text-white'
                : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600'
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      {emphasis === 'WEAKNESSES' && weaknessFocus.length > 0 && (
        <p className="text-xs text-slate-500 dark:text-slate-400">参考薄弱项：{weaknessFocus.join('、')}</p>
      )}
      {weaknessFocus.length === 0 && (
        <p className="text-xs text-slate-400">暂无可验证的低分项，可先围绕简历或八股面试。</p>
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
