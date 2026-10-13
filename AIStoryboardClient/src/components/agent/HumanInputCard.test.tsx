import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HumanInputCard } from './HumanInputCard';
import { useAgentStore, type HumanInputInfo } from '../../stores/agentStore';
import { defaultGenerationPreferences } from '../../lib/generationPreferences';

afterEach(cleanup);

const info: HumanInputInfo = {
  formToken: 'token',
  taskId: 'task',
  formContent: '请确认分镜生成参数',
  actions: [{ id: 'approve', title: '确认' }],
  expirationTime: 0,
  imageModels: [{
    value: 'doubao-seedream-4-0-250828',
    label: 'Seedream 4.0',
    default: 'true',
    params: JSON.stringify({ sizes: ['1K', '2K', '4K'], sizeDefault: '4K', aspectRatios: ['16:9', '1:1'] }),
  }],
};

beforeEach(() => {
  useAgentStore.setState({ generationPreferences: defaultGenerationPreferences() });
});

const selectFor = (label: string) =>
  screen.getByText(label).parentElement?.querySelector('select') as HTMLSelectElement;

describe('HumanInputCard 生成偏好预选', () => {
  it('自动模式不预选，保持模型默认值', () => {
    render(<HumanInputCard info={info} />);
    expect(selectFor('尺寸').value).toBe('4K');
    expect(selectFor('画幅').value).toBe('16:9');
  });

  it('自定义模式按偏好预选，用户仍可改', () => {
    useAgentStore.setState({
      generationPreferences: {
        auto: false,
        ignoreGenerationPlan: false,
        image: { model: 'doubao-seedream-4-0-250828', aspectRatio: '1:1', clarity: '2K', clarityParam: 'size' },
        video: { model: '', aspectRatio: '', clarity: '', clarityParam: '' },
      },
    });
    render(<HumanInputCard info={info} />);
    expect(selectFor('尺寸').value).toBe('2K');
    expect(selectFor('画幅').value).toBe('1:1');
  });
});
