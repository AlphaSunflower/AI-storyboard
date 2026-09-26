import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { agentApi } from '../../api/agent';
import { AgentToolAuditPanel } from './AgentToolAuditPanel';

describe('AgentToolAuditPanel', () => {
  it('renders tool invocation timeline and reference mismatch', async () => {
    vi.spyOn(agentApi, 'getToolAuditConfig').mockResolvedValue({
      data: {
        data: { enabled: true, uiEnabled: true, retentionDays: 90 },
      },
    } as never);
    vi.spyOn(agentApi, 'listToolInvocations').mockResolvedValue({
      data: {
        data: {
          limit: 50,
          nextCursor: null,
          records: [{
            id: 'inv-1',
            toolName: 'generate_storyboard_image',
            toolKind: 'MEDIA_IMAGE',
            source: 'AGENT_SCOPE',
            status: 'COMPLETED',
            resultAssetIds: [],
            expectedReferenceCount: 2,
            resolvedReferenceCount: 1,
            appliedReferenceCount: 1,
            startedAt: new Date().toISOString(),
            durationMs: 1200,
          }],
        },
      },
    } as never);

    render(
      <AgentToolAuditPanel
        taskId="task-1"
        conversationId="conversation-1"
        refreshToken={1}
      />,
    );

    await waitFor(() => {
      expect(screen.queryByText('generate_storyboard_image')).not.toBeNull();
    });
    expect(screen.queryByText('参考数不一致')).not.toBeNull();
  });
});
