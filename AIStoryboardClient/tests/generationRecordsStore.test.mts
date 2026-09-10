import assert from 'node:assert/strict';
import test from 'node:test';

import { parseResultUrls } from '../src/lib/generationRecordParsing.ts';

test('parses string result URL arrays without falling back to one image', () => {
  assert.deepEqual(
    parseResultUrls(JSON.stringify([
      '/api/files/images/one.jpeg',
      '/api/files/images/two.jpeg',
      '/api/files/images/three.jpeg',
    ])),
    [
      '/api/files/images/one.jpeg',
      '/api/files/images/two.jpeg',
      '/api/files/images/three.jpeg',
    ],
  );
});

test('also accepts legacy object result URL arrays', () => {
  assert.deepEqual(
    parseResultUrls(JSON.stringify([
      { url: '/api/files/images/one.jpeg' },
      { url: '/api/files/images/two.jpeg' },
    ])),
    ['/api/files/images/one.jpeg', '/api/files/images/two.jpeg'],
  );
});
