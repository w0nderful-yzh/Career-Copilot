import { useCallback, useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import {
  AlertTriangle,
  CheckCircle2,
  FileCheck2,
  FileDown,
  GitBranch,
  Loader2,
  Sparkles,
  Upload,
} from 'lucide-react';
import {
  historyApi,
  type ResumeVersionItem,
} from '../api/history';

// 简历版本面板（P2-3）：结构化版本列表 + 解析结果确认。
// 解析结果需用户确认后才可作为简历优化（Copilot「优化简历」）的取数基础。

const SOURCE_META: Record<ResumeVersionItem['source'], { label: string; className: string; icon: typeof Upload }> = {
  IMPORT: { label: '原始导入', className: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300', icon: Upload },
  AI_OPTIMIZE: { label: 'AI 优化', className: 'bg-primary-50 text-primary-600 dark:bg-primary-900/50 dark:text-primary-300', icon: Sparkles },
  USER_EDIT: { label: '手动编辑', className: 'bg-blue-50 text-blue-600 dark:bg-blue-900/50 dark:text-blue-300', icon: FileCheck2 },
};

const STATUS_META: Record<ResumeVersionItem['confirmationStatus'], { label: string; className: string }> = {
  PENDING_CONFIRMATION: { label: '待确认', className: 'bg-amber-50 text-amber-600 dark:bg-amber-900/40 dark:text-amber-300' },
  ACTIVE: { label: '已确认', className: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-300' },
  NEED_USER_INFO: { label: '需补录', className: 'bg-red-50 text-red-600 dark:bg-red-900/40 dark:text-red-300' },
};

function StatusBadge({ status }: { status: ResumeVersionItem['confirmationStatus'] }) {
  const meta = STATUS_META[status];
  return (
    <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${meta.className}`}>
      {meta.label}
    </span>
  );
}

/** 缺失字段的人类可读描述 */
function missingFieldLabel(field: string): string {
  if (field.startsWith('basicInfo.name')) return '姓名';
  if (field.startsWith('basicInfo.contact')) return '联系方式（电话/邮箱）';
  if (field === 'education') return '教育经历';
  if (field.startsWith('experience/projects')) return '工作/项目经历（两者均为空，可能解析遗漏）';
  return field;
}

/** 解析确认卡片：待确认 / 需补录时展示 */
function ConfirmCard({
  version,
  onConfirmed,
}: {
  version: ResumeVersionItem;
  onConfirmed: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const needMore = version.confirmationStatus === 'NEED_USER_INFO';
  // 一期不做结构化补录表单（缺失字段少时建议用户重新上传或稍后版本完善），
  // 确认动作对 NEED_USER_INFO 同样生效——用户看过缺失提示后自行判断内容可用性
  const content = version.content;

  const handleConfirm = async () => {
    setConfirming(true);
    setError(null);
    try {
      await historyApi.confirmResumeVersion(version.id);
      onConfirmed();
    } catch (err) {
      setError(err instanceof Error ? err.message : '确认失败，请稍后重试');
    } finally {
      setConfirming(false);
    }
  };

  return (
    <div className={`mt-3 rounded-xl border p-4 ${
      needMore
        ? 'border-red-200 bg-red-50/60 dark:border-red-800/50 dark:bg-red-950/20'
        : 'border-amber-200 bg-amber-50/60 dark:border-amber-800/50 dark:bg-amber-950/20'
    }`}>
      <div className="flex items-start gap-2.5">
        <AlertTriangle className={`w-4.5 h-4.5 mt-0.5 shrink-0 ${
          needMore ? 'text-red-500' : 'text-amber-500'
        }`} />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-slate-800 dark:text-slate-100">
            {needMore ? '解析结果缺少关键字段' : '请确认解析结果'}
          </p>
          {version.missingFields.length > 0 && (
            <ul className="mt-1.5 space-y-0.5 text-xs text-slate-600 dark:text-slate-300">
              {version.missingFields.map((field) => (
                <li key={field}>· 未解析到：{missingFieldLabel(field)}</li>
              ))}
            </ul>
          )}
          {content?.basicInfo?.name && (
            <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
              解析到姓名「{content.basicInfo.name}」、
              {content.education?.length ?? 0} 条教育经历、
              {content.experience?.length ?? 0} 段工作/实习经历、
              {content.projects?.length ?? 0} 个项目、
              {content.skills?.length ?? 0} 条技能。确认后即可在 Copilot 中「优化简历」。
            </p>
          )}
          {error && (
            <p className="mt-2 text-xs text-red-500">{error}</p>
          )}
          <div className="mt-3 flex items-center gap-2">
            <motion.button
              onClick={handleConfirm}
              disabled={confirming}
              className="inline-flex items-center gap-1.5 px-4 py-2 bg-primary-500 text-white rounded-lg text-sm font-medium shadow-sm hover:bg-primary-600 transition-all disabled:opacity-50"
              whileTap={{ scale: 0.98 }}
            >
              {confirming
                ? <Loader2 className="w-4 h-4 animate-spin" />
                : <CheckCircle2 className="w-4 h-4" />}
              {confirming ? '确认中…' : '确认解析结果'}
            </motion.button>
            <span className="text-xs text-slate-400">确认后仍可继续优化迭代</span>
          </div>
        </div>
      </div>
    </div>
  );
}

/** 单个版本卡片 */
function VersionCard({
  version,
  isLatest,
  needsConfirm,
  onConfirmed,
}: {
  version: ResumeVersionItem;
  isLatest: boolean;
  needsConfirm: boolean;
  onConfirmed: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  // P2-4 正式导出（手动）：渲染 → RustFS → 浏览器下载
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const sourceMeta = SOURCE_META[version.source] ?? SOURCE_META.IMPORT;
  const SourceIcon = sourceMeta.icon;
  const content = version.content;

  const handleExportPdf = async () => {
    setExporting(true);
    setExportError(null);
    try {
      const result = await historyApi.exportVersionPdf(version.id);
      // RustFS bucket 非 public-read：经后端代理端点拿字节再触发浏览器下载
      const blob = await historyApi.downloadExportedPdf(result.fileKey);
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = result.filename;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(link.href);
    } catch (err) {
      setExportError(err instanceof Error ? err.message : '导出失败，请稍后重试');
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 overflow-hidden">
      <div className="flex items-center justify-between px-4 py-3">
        <div className="flex items-center gap-3 min-w-0">
          <GitBranch className="w-4 h-4 text-slate-400 shrink-0" />
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-sm font-semibold text-slate-800 dark:text-slate-100">
                V{version.version}
              </span>
              <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${sourceMeta.className}`}>
                <SourceIcon className="w-3 h-3 inline mr-1 -mt-0.5" />
                {sourceMeta.label}
              </span>
              <StatusBadge status={version.confirmationStatus} />
              {isLatest && (
                <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-primary-100 text-primary-600 dark:bg-primary-900/50 dark:text-primary-300">
                  最新
                </span>
              )}
            </div>
            <p className="mt-0.5 text-xs text-slate-400">
              创建于 {new Date(version.createdAt).toLocaleString('zh-CN')}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-3 shrink-0">
          {content && version.confirmationStatus === 'ACTIVE' && (
            <button
              onClick={handleExportPdf}
              disabled={exporting}
              className="flex items-center gap-1 text-xs text-primary-600 dark:text-primary-400 hover:underline disabled:opacity-50"
              title="渲染当前版本为 PDF 并上传存储（手动导出）"
            >
              {exporting ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <FileDown className="w-3.5 h-3.5" />
              )}
              {exporting ? '导出中…' : '导出 PDF'}
            </button>
          )}
          {content && (
            <button
              onClick={() => setExpanded((prev) => !prev)}
              className="text-xs text-primary-600 dark:text-primary-400 hover:underline shrink-0"
            >
              {expanded ? '收起内容' : '查看内容'}
            </button>
          )}
        </div>
      </div>

      {exportError && (
        <p className="mx-4 mb-2 text-xs text-red-500">{exportError}</p>
      )}

      {needsConfirm && version.version === 1 && <ConfirmCard version={version} onConfirmed={onConfirmed} />}

      {expanded && content && (
        <div className="border-t border-slate-100 dark:border-slate-700 px-4 py-3 space-y-3 text-sm">
          <Section title="基本信息" items={[
            [content.basicInfo?.name, '姓名'],
            [content.basicInfo?.phone, '电话'],
            [content.basicInfo?.email, '邮箱'],
            [content.basicInfo?.location, '所在地'],
          ].filter(([v]) => Boolean(v)).map(([v, label]) => `${label}：${v}`)} />
          {(content.education?.length ?? 0) > 0 && (
            <Section
              title="教育经历"
              items={(content.education ?? []).map(
                (edu) => `${edu.school ?? ''} ${edu.major ?? ''} ${edu.degree ?? ''}`.trim(),
              )}
            />
          )}
          {(content.experience?.length ?? 0) > 0 && (
            <div>
              <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">工作/实习经历</p>
              <div className="space-y-2">
                {(content.experience ?? []).map((exp, idx) => (
                  <div key={idx} className="rounded-lg bg-slate-50 dark:bg-slate-700/50 px-3 py-2">
                    <p className="text-xs font-semibold text-slate-700 dark:text-slate-200">
                      {[exp.company, exp.position].filter(Boolean).join(' ')}
                      {(exp.startDate || exp.endDate)
                        ? ` · ${[exp.startDate, exp.endDate].filter(Boolean).join(' - ')}`
                        : ''}
                    </p>
                    <ul className="mt-1 space-y-0.5">
                      {(exp.bullets ?? []).map((bullet, bIdx) => (
                        <li key={bIdx} className="text-xs text-slate-500 dark:text-slate-400">· {bullet}</li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </div>
          )}
          {(content.projects?.length ?? 0) > 0 && (
            <div>
              <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">项目经历</p>
              <div className="space-y-2">
                {(content.projects ?? []).map((project, idx) => (
                  <div key={idx} className="rounded-lg bg-slate-50 dark:bg-slate-700/50 px-3 py-2">
                    <p className="text-xs font-semibold text-slate-700 dark:text-slate-200">
                      {project.name}
                      {project.techStack ? ` · ${project.techStack}` : ''}
                    </p>
                    <ul className="mt-1 space-y-0.5">
                      {(project.bullets ?? []).map((bullet, bIdx) => (
                        <li key={bIdx} className="text-xs text-slate-500 dark:text-slate-400">· {bullet}</li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </div>
          )}
          {(content.skills?.length ?? 0) > 0 && (
            <Section
              title="技能"
              items={(content.skills ?? []).map(
                (skill) => `${skill.category ? `${skill.category}：` : ''}${skill.content ?? ''}`,
              )}
            />
          )}
          {(content.customSections?.length ?? 0) > 0 && (
            <Section
              title="其他段落"
              items={(content.customSections ?? []).flatMap(
                (section) => (section.items ?? []).map((item) => `${section.title ?? ''}：${item}`),
              )}
            />
          )}
        </div>
      )}
    </div>
  );
}

function Section({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <div>
      <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">{title}</p>
      <ul className="space-y-0.5">
        {items.map((item, idx) => (
          <li key={idx} className="text-xs text-slate-500 dark:text-slate-400">· {item}</li>
        ))}
      </ul>
    </div>
  );
}

export default function ResumeVersionPanel({ resumeId }: { resumeId: number }) {
  const [versions, setVersions] = useState<ResumeVersionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadVersions = useCallback(async () => {
    try {
      setError(null);
      const data = await historyApi.getResumeVersions(resumeId);
      setVersions(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载版本失败');
    } finally {
      setLoading(false);
    }
  }, [resumeId]);

  useEffect(() => {
    setLoading(true);
    loadVersions();
  }, [loadVersions]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12 text-sm text-slate-400">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        加载版本中…
      </div>
    );
  }
  if (error) {
    return <p className="py-8 text-center text-sm text-red-500">{error}</p>;
  }
  if (versions.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-slate-200 dark:border-slate-700 py-10 text-center">
        <FileCheck2 className="w-8 h-8 text-slate-300 dark:text-slate-600 mx-auto mb-2" />
        <p className="text-sm text-slate-400">
          简历分析完成后会自动生成结构化版本（用于优化与导出）
        </p>
      </div>
    );
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="space-y-3"
    >
      <div className="flex items-center justify-between">
        <p className="text-sm font-semibold text-slate-700 dark:text-slate-200">
          结构化版本（{versions.length}）
        </p>
        <span className="text-xs text-slate-400">按确认后的版本在 Copilot 中优化简历</span>
      </div>
      {versions.map((version) => (
        <VersionCard
          key={version.id}
          version={version}
          isLatest={version.version === versions[0]?.version}
          needsConfirm={version.confirmationStatus !== 'ACTIVE'}
          onConfirmed={loadVersions}
        />
      ))}
    </motion.div>
  );
}
