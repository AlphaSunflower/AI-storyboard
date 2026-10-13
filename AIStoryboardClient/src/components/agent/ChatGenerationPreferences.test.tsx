import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ChatGenerationPreferences } from './ChatGenerationPreferences';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import {
  GENERATION_PREFERENCES_STORAGE_KEY,
  defaultGenerationPreferences,
} from '../../lib/generationPreferences';
import type { ModelOption } from '../../config';

afterEach(cleanup);

const imageModels: ModelOption[] = [
  {
    value: 'doubao-seedream-4-0-250828',
    label: 'Seedream 4.0',
    default: 'true',
    params: { sizes: ['1K', '2K', '4K'], sizeDefault: '2K', aspectRatios: ['16:9', '1:1'] },
  },
];

const videoModels: ModelOption[] = [
  {
    value: 'doubao-seedance-1-0',
    label: 'Seedance 1.0',
    default: 'true',
    params: { resolutions: ['720P', '1080P'], resolutionDefault: '720P', aspectRatios: ['16:9'] },
  },
  { value: 'doubao-seedance-2-0', label: 'Seedance 2.0', params: { resolutions: ['720P'] } },
];

beforeEach(() => {
  window.localStorage.clear();
  useAgentStore.setState({ generationPreferences: defaultGenerationPreferences() });
  useProjectStore.setState({ imageModelOptions: imageModels, videoModelOptions: videoModels });
});

const open = () => fireEvent.click(screen.getByRole('button', { name: /生成偏好/ }));

describe('ChatGenerationPreferences', () => {
  it('默认显示「自动」，面板按模型能力渲染比例与清晰度', () => {
    render(<ChatGenerationPreferences />);
    expect(screen.getByRole('button', { name: '生成偏好：自动' })).toBeTruthy();

    open();
    expect(screen.getByRole('dialog', { name: '生成偏好' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '智能比例' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '比例 16:9' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '比例 1:1' })).toBeTruthy();
    // 自动态：比例按钮置灰，清晰度显示模型默认值
    expect((screen.getByRole('button', { name: '比例 16:9' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByRole('combobox', { name: '清晰度' }).textContent).toContain('2K');
    expect(screen.getByRole('combobox', { name: '生成模型' }).textContent).toContain('Seedream 4.0');
  });

  it('模型没声明比例时只剩「智能」可选', () => {
    useProjectStore.setState({
      imageModelOptions: [{ value: 'plain', label: 'Plain', default: 'true', params: { sizes: ['2K'] } }],
    });
    render(<ChatGenerationPreferences />);
    open();
    expect(screen.getByRole('button', { name: '智能比例' })).toBeTruthy();
    expect(document.querySelectorAll('.chat-generation-prefs__ratio')).toHaveLength(1);
  });

  it('关掉自动后 chip 变「自定义」，选择比例写入本地存储', () => {
    render(<ChatGenerationPreferences />);
    open();
    fireEvent.click(screen.getByRole('switch', { name: '自动生成偏好' }));

    expect(screen.getByRole('button', { name: '生成偏好：自定义' })).toBeTruthy();
    const ratio = screen.getByRole('button', { name: '比例 16:9' }) as HTMLButtonElement;
    expect(ratio.disabled).toBe(false);
    fireEvent.click(ratio);

    expect(useAgentStore.getState().generationPreferences.image.aspectRatio).toBe('16:9');
    const stored = JSON.parse(window.localStorage.getItem(GENERATION_PREFERENCES_STORAGE_KEY) ?? '{}');
    expect(stored.auto).toBe(false);
    expect(stored.image.aspectRatio).toBe('16:9');
  });

  it('切换图片 / 视频标签各自独立保存偏好', () => {
    render(<ChatGenerationPreferences />);
    open();
    fireEvent.click(screen.getByRole('switch', { name: '自动生成偏好' }));
    fireEvent.click(screen.getByRole('tab', { name: '视频' }));

    expect(screen.getByRole('combobox', { name: '清晰度' }).textContent).toContain('720P');
    // 键盘驱动下拉：Enter 展开 → ↓ 移到第二项 → Enter 选中（避免 jsdom 布局缺失影响指针选择）
    const trigger = screen.getByRole('combobox', { name: '生成模型' });
    fireEvent.keyDown(trigger, { key: 'Enter' });
    fireEvent.keyDown(trigger, { key: 'ArrowDown' });
    fireEvent.keyDown(trigger, { key: 'Enter' });

    expect(useAgentStore.getState().generationPreferences.video.model).toBe('doubao-seedance-2-0');
    expect(useAgentStore.getState().generationPreferences.image.aspectRatio).toBe('');
  });

  it('「忽略对话中的生成方案」独立于自动开关', () => {
    render(<ChatGenerationPreferences />);
    open();
    fireEvent.click(screen.getByRole('switch', { name: '忽略对话中的生成方案' }));

    const preferences = useAgentStore.getState().generationPreferences;
    expect(preferences.ignoreGenerationPlan).toBe(true);
    expect(preferences.auto).toBe(true);
    const stored = JSON.parse(window.localStorage.getItem(GENERATION_PREFERENCES_STORAGE_KEY) ?? '{}');
    expect(stored.ignoreGenerationPlan).toBe(true);
    expect(stored.auto).toBe(true);
  });

  it('禁用态不展开且不可点', () => {
    render(<ChatGenerationPreferences disabled />);
    const trigger = screen.getByRole('button', { name: '生成偏好：自动' }) as HTMLButtonElement;
    expect(trigger.disabled).toBe(true);
    fireEvent.click(trigger);
    expect(screen.queryByRole('dialog', { name: '生成偏好' })).toBeNull();
  });
});
