import assert from 'node:assert/strict';
import test from 'node:test';

import { SUPPORTED_CODE_LANGUAGES, resolveCodeLanguage } from './codeLanguage.ts';

test('已注册语言原样返回', () => {
  assert.equal(resolveCodeLanguage('java'), 'java');
  assert.equal(resolveCodeLanguage('python'), 'python');
  assert.equal(resolveCodeLanguage('sql'), 'sql');
});

test('大小写与首尾空白被归一化', () => {
  assert.equal(resolveCodeLanguage('  JAVA  '), 'java');
  assert.equal(resolveCodeLanguage('TypeScript'), 'typescript');
});

test('常见别名映射到已注册语言', () => {
  assert.equal(resolveCodeLanguage('js'), 'javascript');
  assert.equal(resolveCodeLanguage('py'), 'python');
  assert.equal(resolveCodeLanguage('sh'), 'bash');
  assert.equal(resolveCodeLanguage('yml'), 'yaml');
  assert.equal(resolveCodeLanguage('html'), 'markup');
  assert.equal(resolveCodeLanguage('c++'), 'cpp');
  assert.equal(resolveCodeLanguage('golang'), 'go');
});

test('未知语言返回 null（调用方按纯文本渲染，不依赖高亮器兜底）', () => {
  assert.equal(resolveCodeLanguage('brainfuck'), null);
  assert.equal(resolveCodeLanguage('未知语言'), null);
  assert.equal(resolveCodeLanguage('text'), null);
});

test('空标注与缺失值返回 null', () => {
  assert.equal(resolveCodeLanguage(undefined), null);
  assert.equal(resolveCodeLanguage(null), null);
  assert.equal(resolveCodeLanguage(''), null);
  assert.equal(resolveCodeLanguage('   '), null);
});

test('解析结果必定落在已注册语言集合内', () => {
  const samples = ['js', 'py', 'html', 'JAVA', 'yml', 'c#', 'unknown-lang', ''];
  for (const sample of samples) {
    const resolved = resolveCodeLanguage(sample);
    if (resolved !== null) {
      assert.ok(
        (SUPPORTED_CODE_LANGUAGES as readonly string[]).includes(resolved),
        `${sample} 解析出未注册语言 ${resolved}`
      );
    }
  }
});
