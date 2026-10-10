import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useSideChatSelectionStore } from '../../stores/sideChatSelectionStore';
import { SideChatContextChip } from './SideChatContextChip';

describe('SideChatContextChip', () => {
  beforeEach(() => {
    useSideChatSelectionStore.getState().clear();
  });

  afterEach(cleanup);

  it('shows the selected text count and can remove one fragment', () => {
    useSideChatSelectionStore.getState().addSelection({
      text: '第一段需要追问的文本',
      messageId: 'm1',
    });
    useSideChatSelectionStore.getState().addSelection({
      text: '第二段需要追问的文本',
      messageId: 'm1',
    });
    render(<SideChatContextChip />);

    expect(screen.getByText('2 个已选文本片段')).toBeTruthy();
    expect(screen.getByText('第一段需要追问的文本')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '移除文本片段 1' }));

    expect(useSideChatSelectionStore.getState().selections).toHaveLength(1);
    expect(useSideChatSelectionStore.getState().selections[0].text)
      .toBe('第二段需要追问的文本');
  });
});
