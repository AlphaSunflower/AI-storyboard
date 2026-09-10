import assert from 'node:assert/strict';
import test from 'node:test';

import { buildSceneImageSubmitParams } from '../src/components/agent/sceneReviewGeneration.ts';

test('builds per-scene image params for every selected scene', () => {
  const payload = buildSceneImageSubmitParams(
    ['scene-1', 'scene-2'],
    {
      'scene-1': { imageModel: 'doubao-seedream-4-0-250828', imageSize: '1K', imageQuality: 'high', imageN: 2 },
      'scene-2': { imageModel: 'doubao-seedream-4-0-250828', imageSize: '2K', imageN: 3 },
    },
    {},
    'gpt-image-2',
  );
  const parsed = JSON.parse(payload.sceneImageParams);

  assert.equal(Object.keys(parsed).length, 2);
  assert.equal(parsed['scene-1'].n, 2);
  assert.equal(parsed['scene-2'].n, 3);
  assert.equal(parsed['scene-2'].size, '2K');
});

test('row overrides win and default model/options fill missing values', () => {
  const payload = buildSceneImageSubmitParams(
    ['scene-3'],
    { 'scene-3': { imageModel: 'old-model', imageSize: 'old-size', imageN: 1 } },
    { 'scene-3': { imageModel: 'doubao-seedream-4-0-250828', imageN: '4' } },
    'default-model',
  );
  const parsed = JSON.parse(payload.sceneImageParams)['scene-3'];

  assert.equal(parsed.model, 'doubao-seedream-4-0-250828');
  assert.equal(parsed.n, 4);
  assert.equal(parsed.size, 'old-size');
});
