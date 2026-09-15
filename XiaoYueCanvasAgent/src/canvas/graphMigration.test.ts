import { describe, expect, it } from 'vitest';
import { migrateCanvasGraphV1ToV2 } from './graphMigration';

const legacyGraph = {
  schemaVersion: 1 as const,
  viewport: { x: 0, y: 0, zoom: 1 },
  nodes: [
    { id: 'prompt-1', type: 'media' as const, position: { x: 0, y: 0 }, data: { kind: 'prompt' as const, label: '提示词', prompt: '猫', status: 'idle' as const, accent: '#fff', promptText: '橘猫' } },
    { id: 'script-1', type: 'media' as const, position: { x: 300, y: 0 }, data: { kind: 'script' as const, label: '剧本', prompt: '', status: 'idle' as const, accent: '#fff', scriptText: '第一幕' } },
    { id: 'story-1', type: 'media' as const, position: { x: 600, y: 0 }, data: { kind: 'storyboard' as const, label: '分镜', prompt: '', status: 'idle' as const, accent: '#fff', frames: [{ label: '镜头1', description: '开场' }, { label: '镜头2', prompt: '转身' }] } },
    { id: 'image-1', type: 'media' as const, position: { x: 0, y: 300 }, data: { kind: 'image' as const, label: '图片', prompt: '', status: 'success' as const, accent: '#fff', assetId: 'asset-1' } },
    { id: 'gen-1', type: 'media' as const, position: { x: 300, y: 300 }, data: { kind: 'imageGen' as const, label: 'AI 图片', prompt: '改成蓝色', status: 'idle' as const, accent: '#fff' } },
    { id: 'video-gen-1', type: 'media' as const, position: { x: 600, y: 300 }, data: { kind: 'videoGen' as const, label: '生视频', prompt: '动起来', status: 'idle' as const, accent: '#fff', videoMode: 'image2video' } },
    { id: 'sequence-1', type: 'media' as const, position: { x: 900, y: 0 }, data: { kind: 'sequence' as const, label: '序列', prompt: '', status: 'idle' as const, accent: '#fff', children: ['prompt-1'] } },
  ],
  edges: [
    { id: 'edge-image-gen', source: 'image-1', sourceHandle: 'right', target: 'gen-1', targetHandle: 'left' },
    { id: 'edge-gen-video', source: 'gen-1', sourceHandle: 'right', target: 'video-gen-1', targetHandle: 'left' },
    { id: 'edge-script-story', source: 'script-1', sourceHandle: 'right', target: 'story-1', targetHandle: 'left' },
    { id: 'edge-invalid', source: 'prompt-1', sourceHandle: 'right', target: 'image-1', targetHandle: 'left' },
  ],
};

describe('migrateCanvasGraphV1ToV2', () => {
  it('normalizes legacy node kinds and expands storyboard frames', () => {
    const migrated = migrateCanvasGraphV1ToV2(legacyGraph);
    const kinds = migrated.nodes.map((node) => node.data.kind);

    expect(migrated.schemaVersion).toBe(2);
    expect(kinds).toContain('text');
    expect(kinds).toContain('storyboardPlanner');
    expect(kinds).toContain('shot');
    expect(kinds).not.toContain('prompt');
    expect(kinds).not.toContain('sequence');
    expect(kinds).not.toContain('storyboard');
    expect(migrated.nodes.filter((node) => node.data.kind === 'shot')).toHaveLength(2);
  });

  it('rewrites legacy edges onto concrete handles and reports invalid edges', () => {
    const migrated = migrateCanvasGraphV1ToV2(legacyGraph);
    const valid = migrated.edges.find((edge) => edge.id === 'edge-image-gen');
    const invalid = migrated.edges.find((edge) => edge.id === 'edge-invalid');

    expect(valid).toMatchObject({
      source: 'image-1',
      sourceHandle: 'image',
      target: 'gen-1',
      targetHandle: 'source',
    });
    expect(invalid?.data).toMatchObject({
      migrationStatus: 'migration_invalid',
    });
    expect(migrated.migrationReport?.invalidEdges).toEqual([
      expect.objectContaining({ edgeId: 'edge-gen-video' }),
      expect.objectContaining({ edgeId: 'edge-invalid' }),
    ]);
  });

  it('derives image generation modes from incoming image edges', () => {
    const migrated = migrateCanvasGraphV1ToV2(legacyGraph);
    const generator = migrated.nodes.find((node) => node.id === 'gen-1');
    const video = migrated.nodes.find((node) => node.id === 'video-gen-1');

    expect(generator?.data.kind).toBe('imageToImage');
    expect(video?.data.kind).toBe('imageToVideo');
  });
});
