import assert from 'node:assert/strict';
import test from 'node:test';

import { getSceneImageUrls, resolveSelectedSceneId } from '../src/lib/sceneImages.ts';

test('returns all comma-separated scene images with primary fallback', () => {
  assert.deepEqual(
    getSceneImageUrls({
      imageUrl: '/api/files/images/one.jpeg',
      imageUrls: '/api/files/images/one.jpeg,/api/files/images/two.jpeg',
    }),
    ['/api/files/images/one.jpeg', '/api/files/images/two.jpeg'],
  );
});

test('accepts array imageUrls and removes blanks', () => {
  assert.deepEqual(
    getSceneImageUrls({
      imageUrl: '/api/files/images/fallback.jpeg',
      imageUrls: ['/api/files/images/a.jpeg', '', '/api/files/images/b.jpeg'],
    }),
    ['/api/files/images/a.jpeg', '/api/files/images/b.jpeg'],
  );
});

test('falls back to imageUrl when imageUrls is empty', () => {
  assert.deepEqual(
    getSceneImageUrls({ imageUrl: '/api/files/images/only.jpeg', imageUrls: '' }),
    ['/api/files/images/only.jpeg'],
  );
});

test('keeps selected scene when refreshing the same project', () => {
  assert.equal(
    resolveSelectedSceneId('project-1', 'project-1', 'scene-2', ['scene-1', 'scene-2']),
    'scene-2',
  );
});

test('resets selected scene when switching projects', () => {
  assert.equal(
    resolveSelectedSceneId('project-1', 'project-2', 'scene-2', ['scene-1', 'scene-2']),
    null,
  );
});
