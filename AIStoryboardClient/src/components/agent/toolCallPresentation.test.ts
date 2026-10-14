import { describe, expect, it } from 'vitest';
import {
  TOOL_PREVIEW_CHARS,
  toolDisplayName,
  toolIconKey,
  toolOutcomeNote,
  toolPreviewText,
  toolStatusLabel,
  toolStatusTone,
} from './toolCallPresentation';

describe('toolDisplayName', () => {
  it('已收录的工具给中文动作名', () => {
    expect(toolDisplayName('run_python_script')).toBe('执行 Python 脚本');
    expect(toolDisplayName('web_fetch')).toBe('抓取网页');
    expect(toolDisplayName('generate_image')).toBe('生成图片');
    expect(toolDisplayName('load_skill_through_path')).toBe('读取技能');
  });

  it('未收录的工具回落成去下划线的原名，而不是空白', () => {
    expect(toolDisplayName('some_new_tool')).toBe('some new tool');
    expect(toolDisplayName('another-one')).toBe('another one');
  });

  it('空名回落为「工具调用」', () => {
    expect(toolDisplayName('')).toBe('工具调用');
    expect(toolDisplayName('   ')).toBe('工具调用');
  });
});

describe('toolStatusLabel / toolStatusTone', () => {
  it('已知状态逐字保留既有文案', () => {
    expect(toolStatusLabel('node_started')).toBe('调用中');
    expect(toolStatusLabel('node_running')).toBe('执行中');
    expect(toolStatusLabel('node_completed')).toBe('已完成');
    expect(toolStatusLabel('node_failed')).toBe('失败');
  });

  it('未知状态回落到原文，再兜底「执行中」', () => {
    expect(toolStatusLabel('node_weird')).toBe('node_weird');
    expect(toolStatusLabel('')).toBe('执行中');
  });

  it('状态映射到语气色阶', () => {
    expect(toolStatusTone('node_started')).toBe('running');
    expect(toolStatusTone('node_running')).toBe('running');
    expect(toolStatusTone('node_completed')).toBe('done');
    expect(toolStatusTone('node_failed')).toBe('failed');
    expect(toolStatusTone('whatever')).toBe('idle');
  });
});

describe('toolOutcomeNote', () => {
  it('载荷自己声明失败时给出提示', () => {
    // 截图里的真实形态：传输层 node_completed，但工具结果是 TOOL_FAILED
    expect(toolOutcomeNote('{"ok":false,"tool":"web_fetch","resultStatus":"error"}')).toBe('结果报错');
    expect(toolOutcomeNote('{"resultStatus":"error"}')).toBe('结果报错');
  });

  it('正常输出不误报', () => {
    expect(toolOutcomeNote('{"ok":true,"results":[]}')).toBeNull();
    expect(toolOutcomeNote('{"resultStatus":"ok"}')).toBeNull();
  });

  it('非 JSON / 空值 / 数组一律不表态', () => {
    expect(toolOutcomeNote('print("hi")')).toBeNull();
    expect(toolOutcomeNote('')).toBeNull();
    expect(toolOutcomeNote('   ')).toBeNull();
    expect(toolOutcomeNote('{"ok":false')).toBeNull();
    expect(toolOutcomeNote('[{"ok":false}]')).toBeNull();
  });
});

describe('toolPreviewText', () => {
  it('优先输出，没有输出时退回入参', () => {
    expect(toolPreviewText({ text: 'print("hi")', input: '{"code":"..."}' })).toBe('print("hi")');
    expect(toolPreviewText({ text: '', input: '{"code":"..."}' })).toBe('{"code":"..."}');
    expect(toolPreviewText({ text: '', input: '' })).toBe('');
  });

  it('按上限截断且不追加省略号（截断表现交给 CSS）', () => {
    const preview = toolPreviewText({ text: 'x'.repeat(TOOL_PREVIEW_CHARS + 40), input: '' });
    expect(preview).toHaveLength(TOOL_PREVIEW_CHARS);
    expect(preview.endsWith('…')).toBe(false);
  });
});

describe('toolIconKey', () => {
  it('按工具语义归类，供渲染层挑图标', () => {
    expect(toolIconKey('generate_image')).toBe('image');
    expect(toolIconKey('generate_video')).toBe('video');
    expect(toolIconKey('generate_storyboard_image')).toBe('storyboard');
    expect(toolIconKey('web_search')).toBe('search');
    expect(toolIconKey('write_file')).toBe('file');
    expect(toolIconKey('run_python_script')).toBe('terminal');
    expect(toolIconKey('load_skill_through_path')).toBe('skill');
    expect(toolIconKey('')).toBe('generic');
    expect(toolIconKey('totally_unknown')).toBe('generic');
  });
});
