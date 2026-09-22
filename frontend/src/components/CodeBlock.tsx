import { lazy, Suspense, useState } from 'react';
// 直接引单主题文件：走 styles/prism 桶导出会把全部主题（40+ 套）一起打进包
import oneDark from 'react-syntax-highlighter/dist/esm/styles/prism/one-dark';
import { Check, Copy } from 'lucide-react';
import {
  resolveCodeLanguage,
  type SupportedCodeLanguage,
} from '../utils/codeLanguage';

// 性能优化：高亮器改用 prism-light 并只注册白名单语言。
//
// 默认 prism 构建自带 300 种语言语法（异步 chunk 约 690KB），而实际用到的语言不到 30 种。
// 语言必须写成显式静态字符串的 import()，Vite 才能静态分析并切成按需 chunk；
// 写成 import(`...${lang}`) 模板字符串会被判定为不可分析，整包语法又会被拉回来。
//
// Record<SupportedCodeLanguage, ...> 的类型约束保证这里与白名单一一对应，新增语言时编译期即报错。
const LANGUAGE_LOADERS: Record<
  SupportedCodeLanguage,
  () => Promise<{ default: unknown }>
> = {
  java: () => import('react-syntax-highlighter/dist/esm/languages/prism/java'),
  python: () => import('react-syntax-highlighter/dist/esm/languages/prism/python'),
  javascript: () => import('react-syntax-highlighter/dist/esm/languages/prism/javascript'),
  typescript: () => import('react-syntax-highlighter/dist/esm/languages/prism/typescript'),
  jsx: () => import('react-syntax-highlighter/dist/esm/languages/prism/jsx'),
  tsx: () => import('react-syntax-highlighter/dist/esm/languages/prism/tsx'),
  sql: () => import('react-syntax-highlighter/dist/esm/languages/prism/sql'),
  bash: () => import('react-syntax-highlighter/dist/esm/languages/prism/bash'),
  json: () => import('react-syntax-highlighter/dist/esm/languages/prism/json'),
  yaml: () => import('react-syntax-highlighter/dist/esm/languages/prism/yaml'),
  markup: () => import('react-syntax-highlighter/dist/esm/languages/prism/markup'),
  css: () => import('react-syntax-highlighter/dist/esm/languages/prism/css'),
  markdown: () => import('react-syntax-highlighter/dist/esm/languages/prism/markdown'),
  c: () => import('react-syntax-highlighter/dist/esm/languages/prism/c'),
  cpp: () => import('react-syntax-highlighter/dist/esm/languages/prism/cpp'),
  csharp: () => import('react-syntax-highlighter/dist/esm/languages/prism/csharp'),
  go: () => import('react-syntax-highlighter/dist/esm/languages/prism/go'),
  kotlin: () => import('react-syntax-highlighter/dist/esm/languages/prism/kotlin'),
  rust: () => import('react-syntax-highlighter/dist/esm/languages/prism/rust'),
  php: () => import('react-syntax-highlighter/dist/esm/languages/prism/php'),
  ruby: () => import('react-syntax-highlighter/dist/esm/languages/prism/ruby'),
  scala: () => import('react-syntax-highlighter/dist/esm/languages/prism/scala'),
  swift: () => import('react-syntax-highlighter/dist/esm/languages/prism/swift'),
  diff: () => import('react-syntax-highlighter/dist/esm/languages/prism/diff'),
  ini: () => import('react-syntax-highlighter/dist/esm/languages/prism/ini'),
};

// 高亮器与语言语法一起异步加载：首屏不为「可能出现的代码块」付出体积
const SyntaxHighlighter = lazy(async () => {
  const [mod, grammars] = await Promise.all([
    import('react-syntax-highlighter/dist/esm/prism-light'),
    Promise.all(
      (Object.keys(LANGUAGE_LOADERS) as SupportedCodeLanguage[]).map(async (name) => ({
        name,
        grammar: (await LANGUAGE_LOADERS[name]()).default,
      })),
    ),
  ]);
  const Highlighter = mod.default;
  grammars.forEach(({ name, grammar }) => {
    // 语言语法的导出类型由库自身定义，此处只需原样透传
    (Highlighter.registerLanguage as (lang: string, syntax: unknown) => void)(name, grammar);
  });
  return { default: Highlighter };
});

interface CodeBlockProps {
  language?: string;
  children: string;
}

export default function CodeBlock({ language, children }: CodeBlockProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(children);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('复制失败:', err);
    }
  };

  // 清理代码内容
  const code = children?.trim() || '';
  // 未注册语言返回 null：直接按纯文本渲染，不把未知标注交给高亮器兜底
  const highlightLanguage = resolveCodeLanguage(language);

  return (
    <div className="relative group my-3">
      {/* 语言标签和复制按钮 */}
      <div className="flex items-center justify-between px-4 py-2 bg-slate-700 rounded-t-xl border-b border-slate-600">
        <span className="text-xs text-slate-400 font-mono">
          {language || 'code'}
        </span>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1.5 px-2 py-1 text-xs text-slate-400 hover:text-white hover:bg-slate-600 rounded transition-colors"
          title="复制代码"
        >
          {copied ? (
            <>
              <Check className="w-3.5 h-3.5 text-green-400" />
              <span className="text-green-400">已复制</span>
            </>
          ) : (
            <>
              <Copy className="w-3.5 h-3.5" />
              <span>复制</span>
            </>
          )}
        </button>
      </div>

      {/* 代码内容 */}
      <div className="bg-[#282c34] rounded-b-xl text-sm leading-6">
        {highlightLanguage === null ? (
          <pre className="m-0 overflow-x-auto p-4 font-mono text-xs text-slate-200">
            <code>{code}</code>
          </pre>
        ) : (
          <Suspense fallback={
            <div className="p-4 text-slate-400 font-mono text-xs">Loading code...</div>
          }>
            <SyntaxHighlighter
              language={highlightLanguage}
              style={oneDark}
              customStyle={{
                margin: 0,
                borderTopLeftRadius: 0,
                borderTopRightRadius: 0,
                borderBottomLeftRadius: '0.75rem',
                borderBottomRightRadius: '0.75rem',
                fontSize: '0.875rem',
                lineHeight: '1.5',
              }}
              showLineNumbers={code.split('\n').length > 3}
              wrapLines
            >
              {code}
            </SyntaxHighlighter>
          </Suspense>
        )}
      </div>
    </div>
  );
}
