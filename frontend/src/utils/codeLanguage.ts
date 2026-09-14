/**
 * 代码块语言归一化（性能优化）。
 *
 * react-syntax-highlighter 的默认 prism 构建自带 300 种语言语法，打包后是约 690KB 的
 * 异步 chunk。改为 prism-light 后只注册下面这份白名单，未列出的语言不走高亮、
 * 直接按纯文本渲染——这比对未知语言依赖高亮器兜底更确定，也不会把整包语法拉回来。
 *
 * 注意：白名单必须与 CodeBlock 的 LANGUAGE_LOADERS 键保持一致，新增语言时两处都要加。
 */

/** 已注册的高亮语言 */
export const SUPPORTED_CODE_LANGUAGES = [
  'java',
  'python',
  'javascript',
  'typescript',
  'jsx',
  'tsx',
  'sql',
  'bash',
  'json',
  'yaml',
  'markup',
  'css',
  'markdown',
  'c',
  'cpp',
  'csharp',
  'go',
  'kotlin',
  'rust',
  'php',
  'ruby',
  'scala',
  'swift',
  'diff',
  'ini',
] as const;

export type SupportedCodeLanguage = (typeof SUPPORTED_CODE_LANGUAGES)[number];

const SUPPORTED_SET: ReadonlySet<string> = new Set<string>(SUPPORTED_CODE_LANGUAGES);

/**
 * 常见别名 → 已注册语言。
 *
 * LLM 输出的语言标注并不统一（js / py / sh / yml / html 都很常见），
 * 不做映射会让这些代码块退化成纯文本。
 */
const LANGUAGE_ALIASES: Record<string, SupportedCodeLanguage> = {
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  node: 'javascript',
  ts: 'typescript',
  py: 'python',
  python3: 'python',
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  console: 'bash',
  yml: 'yaml',
  html: 'markup',
  xml: 'markup',
  svg: 'markup',
  vue: 'markup',
  md: 'markdown',
  'c++': 'cpp',
  cc: 'cpp',
  hpp: 'cpp',
  cs: 'csharp',
  'c#': 'csharp',
  golang: 'go',
  kt: 'kotlin',
  rs: 'rust',
  rb: 'ruby',
  toml: 'ini',
  conf: 'ini',
  properties: 'ini',
  patch: 'diff',
};

/**
 * 把代码块标注的语言解析为「已注册语言」。
 *
 * @returns 已注册语言名；返回 null 表示不走高亮，调用方应按纯文本渲染
 *          （未知语言、空标注、非字符串都属此列）。
 */
export function resolveCodeLanguage(raw?: string | null): SupportedCodeLanguage | null {
  if (!raw || typeof raw !== 'string') {
    return null;
  }
  const normalized = raw.trim().toLowerCase();
  if (!normalized) {
    return null;
  }
  const canonical = LANGUAGE_ALIASES[normalized] ?? normalized;
  return SUPPORTED_SET.has(canonical) ? (canonical as SupportedCodeLanguage) : null;
}
