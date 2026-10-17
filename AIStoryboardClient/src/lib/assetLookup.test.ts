import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentAsset } from '../api/agent';
import { useAgentStore } from '../stores/agentStore';
import {
  assetMatchesUrl,
  mediaObjectKey,
  pickBestAssetMatch,
  resolveConversationAssetByUrl,
  sameMediaObject,
} from './assetLookup';

function asset(patch: Partial<AgentAsset> = {}): AgentAsset {
  return {
    id: 'asset-1',
    conversationId: 'conversation-1',
    type: 'image',
    url: '/api/files/images/one.png',
    prompt: null,
    model: null,
    status: 'completed',
    taskId: null,
    error: null,
    createdAt: '2026-10-10T00:00:00.000Z',
    ...patch,
  };
}

describe('mediaObjectKey', () => {
  it('drops origin, query and the /api/files/ prefix', () => {
    expect(mediaObjectKey('/api/files/images/a.png')).toBe('images/a.png');
    expect(mediaObjectKey('http://localhost:8082/api/files/images/a.png?e=1&s=abc')).toBe('images/a.png');
    expect(mediaObjectKey('https://cdn.example.com/api/files/videos/b.mp4#t=2')).toBe('videos/b.mp4');
    expect(mediaObjectKey('images/c.png')).toBe('images/c.png');
  });

  it('returns null for addresses that are not object-storage media', () => {
    expect(mediaObjectKey('https://cdn.example.com/outside.png')).toBeNull();
    expect(mediaObjectKey('data:image/png;base64,AAAA')).toBeNull();
    expect(mediaObjectKey('')).toBeNull();
    expect(mediaObjectKey(null)).toBeNull();
  });
});

describe('sameMediaObject', () => {
  it('matches a signed url against the bare one in the message', () => {
    // 这就是线上那条 bug：会话资产接口返回的是签名地址，聊天正文里是裸地址。
    expect(sameMediaObject(
      '/api/files/images/a.png?e=1770000000&s=9f2c',
      '/api/files/images/a.png',
    )).toBe(true);
  });

  it('matches across origin and port', () => {
    expect(sameMediaObject(
      'http://localhost:8080/api/files/images/a.png',
      'http://backend:8082/api/files/images/a.png',
    )).toBe(true);
  });

  it('keeps different files apart', () => {
    expect(sameMediaObject('/api/files/images/a.png', '/api/files/images/b.png')).toBe(false);
    expect(sameMediaObject('/api/files/images/a.png', '/api/files/files/a.png')).toBe(false);
  });

  it('falls back to strict equality when there is no object key', () => {
    expect(sameMediaObject('https://cdn.example.com/x.png', 'https://cdn.example.com/x.png')).toBe(true);
    expect(sameMediaObject('https://cdn.example.com/x.png', 'https://cdn.example.com/y.png')).toBe(false);
  });
});

describe('assetMatchesUrl', () => {
  it('matches the main url even when it is signed', () => {
    const record = asset({ url: '/api/files/images/a.png?e=1&s=x' });
    expect(assetMatchesUrl(record, '/api/files/images/a.png')).toBe(true);
  });

  it('matches any url inside a multi-image record', () => {
    const record = asset({
      url: '/api/files/images/a.png?e=1&s=x',
      resultUrls: '["/api/files/images/a.png?e=1&s=x","/api/files/images/b.png?e=1&s=y"]',
    });
    expect(assetMatchesUrl(record, '/api/files/images/b.png')).toBe(true);
    expect(assetMatchesUrl(record, '/api/files/images/c.png')).toBe(false);
  });
});

describe('pickBestAssetMatch', () => {
  it('prefers the record that can answer how the image was made', () => {
    const thin = asset({ id: 'thin', url: '/api/files/images/a.png?e=1' });
    const rich = asset({
      id: 'rich',
      url: '/api/files/images/a.png?e=2',
      prompt: '精细国风东方龙图案',
      model: 'Seedream 4.5',
    });

    expect(pickBestAssetMatch([thin, rich], '/api/files/images/a.png')?.id).toBe('rich');
    expect(pickBestAssetMatch([rich, thin], '/api/files/images/a.png')?.id).toBe('rich');
  });

  it('returns null when nothing matches', () => {
    expect(pickBestAssetMatch([asset()], '/api/files/images/other.png')).toBeNull();
    expect(pickBestAssetMatch([], '/api/files/images/a.png')).toBeNull();
  });
});

describe('resolveConversationAssetByUrl', () => {
  afterEach(() => {
    useAgentStore.setState({ assets: null });
    vi.restoreAllMocks();
  });

  it('finds the record in the already-loaded sidebar page without any request', async () => {
    const lookup = vi.spyOn(agentApi, 'listAssets');
    useAgentStore.setState({
      assets: {
        records: [asset({ url: '/api/files/images/a.png?e=1&s=x', prompt: '侧边栏里那条' })],
        total: 1,
        page: 1,
        size: 20,
      },
    });

    const found = await resolveConversationAssetByUrl('conversation-1', '/api/files/images/a.png');

    expect(found?.prompt).toBe('侧边栏里那条');
    expect(lookup).not.toHaveBeenCalled();
  });

  it('asks the conversation assets api when the loaded page does not have it', async () => {
    const lookup = vi.spyOn(agentApi, 'listAssets').mockResolvedValue({
      data: { data: { records: [asset({ url: '/api/files/images/a.png?e=1', prompt: '从接口查到的' })], total: 1, page: 1, size: 50 } },
    } as never);
    useAgentStore.setState({ assets: { records: [], total: 0, page: 1, size: 20 } });

    const found = await resolveConversationAssetByUrl('conversation-1', '/api/files/images/a.png');

    expect(lookup).toHaveBeenCalledWith('conversation-1', 1, 50);
    expect(found?.prompt).toBe('从接口查到的');
  });

  it('returns null instead of throwing when the api fails', async () => {
    vi.spyOn(agentApi, 'listAssets').mockRejectedValue(new Error('boom'));
    useAgentStore.setState({ assets: { records: [], total: 0, page: 1, size: 20 } });

    expect(await resolveConversationAssetByUrl('conversation-1', '/api/files/images/a.png')).toBeNull();
  });

  it('does not call the api without a conversation', async () => {
    const lookup = vi.spyOn(agentApi, 'listAssets');
    useAgentStore.setState({ assets: null });

    expect(await resolveConversationAssetByUrl(null, '/api/files/images/a.png')).toBeNull();
    expect(lookup).not.toHaveBeenCalled();
  });
});
