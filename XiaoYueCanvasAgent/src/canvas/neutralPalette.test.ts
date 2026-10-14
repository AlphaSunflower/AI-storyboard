import { readFileSync, readdirSync } from 'node:fs';
import { join, normalize, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * /canvas 子站的配色守卫：整个子站只有白灰阶，不允许出现任何色相。
 *
 * 判定标准是「通道极差」：一个颜色的 max(r,g,b) - min(r,g,b) 必须 ≤ 2。
 * 只用 #f4f4f5 / rgba(244,244,245,·) 这一族留了 1 的极差（既有的「白」），
 * 其余一律纯灰，所以绿色、警示橙、错误红、视频蓝紫、节点分类色都会被拦下。
 *
 * 白名单只有「用户绘制数据」：蒙版色板与标注画笔默认色——那是用户选的颜色，
 * 不是界面配色。
 */

const ROOT = normalize(join(__dirname, '..', '..'));

const ALLOWED_FILES = new Set([
  normalize('src/utils/redrawMask.ts'),
]);

// 按文件白名单的单个色值：标注画笔默认色（用户绘制数据）
const ALLOWED_LITERALS: Record<string, string[]> = {
  [normalize('src/components/nodes/ImageGenNode.tsx')]: ['#ff5f5f'],
};

const MAX_CHANNEL_SPREAD = 2;

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules') continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (/\.(tsx|ts|css)$/.test(entry.name) && !/\.test\.(ts|tsx)$/.test(entry.name)) out.push(full);
  }
  return out;
}

type Hit = { file: string; literal: string; spread: number };

function scanColors(file: string): Hit[] {
  const relativePath = normalize(relative(ROOT, file));
  const allowed = ALLOWED_LITERALS[relativePath] ?? [];
  const found: Hit[] = [];
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  const hex = /#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})\b/g;
  const rgba = /rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*[0-9.]+%?\s*)?\)/g;

  lines.forEach((line, index) => {
    const push = (literal: string, rgb: [number, number, number]) => {
      if (allowed.includes(literal.toLowerCase())) return;
      const spread = Math.max(...rgb) - Math.min(...rgb);
      if (spread > MAX_CHANNEL_SPREAD) found.push({ file: `${relativePath}:${index + 1}`, literal, spread });
    };
    let match: RegExpExecArray | null;
    while ((match = rgba.exec(line))) {
      push(match[0].toLowerCase(), [Number(match[1]), Number(match[2]), Number(match[3])]);
    }
    while ((match = hex.exec(line))) {
      let body = match[1];
      if (body.length === 3) body = body.split('').map((char) => char + char).join('');
      if (body.length === 8) body = body.slice(0, 6);
      push(match[0].toLowerCase(), [
        parseInt(body.slice(0, 2), 16),
        parseInt(body.slice(2, 4), 16),
        parseInt(body.slice(4, 6), 16),
      ]);
    }
  });
  return found;
}

describe('/canvas 配色守卫', () => {
  it('界面里不存在任何带色相的颜色（只剩白灰阶）', () => {
    const files = [
      ...walk(join(ROOT, 'src')),
      join(ROOT, 'tailwind.config.js'),
      // index.html 的 theme-color 也算声明出去的配色，一起守
      join(ROOT, 'index.html'),
    ].filter((file) => !ALLOWED_FILES.has(normalize(relative(ROOT, file))));

    const hits = files.flatMap(scanColors);
    expect(hits.map((hit) => `${hit.file}  ${hit.literal}  (spread ${hit.spread})`)).toEqual([]);
  });

  it('旧主题的绿/橙/红/蓝紫标签一个都不留', () => {
    const css = readFileSync(join(ROOT, 'src', 'styles.css'), 'utf8').toLowerCase();
    const banned = [
      '#11201c', '#14231f', '#1c3029', '#254239', '#1a332b', '#e5ff89', '#9ac83e',
      '#8f9cff', '#ff765f', '#ff8a3d', 'd7ff54', 'deep green',
      'rgba(215,255,84', 'rgba(143,156,255', 'rgba(255,118,95', 'rgba(74,222,128',
    ];
    const remaining = banned.filter((token) => css.includes(token));
    expect(remaining).toEqual([]);
  });

  it('中性色板是所有颜色的来源', () => {
    const css = readFileSync(join(ROOT, 'src', 'styles.css'), 'utf8');
    for (const token of ['--n-canvas', '--n-panel', '--n-raised', '--line', '--line-strong', '--text', '--solid', '--solid-ink']) {
      expect(css).toContain(token);
    }
  });
});
