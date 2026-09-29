import { findAndReplace } from 'mdast-util-find-and-replace';
import type { Root } from 'mdast';
import type { Plugin } from 'unified';

/**
 * Turn single newlines inside text into explicit `break` nodes.
 *
 * The chat container no longer uses `white-space: pre-wrap` (it produced extra
 * blank lines between markdown blocks), so soft newlines would otherwise
 * collapse. This keeps the old "single newline = line break" behavior without
 * reintroducing the block spacing bug.
 */
export const remarkBreaks: Plugin<[], Root> = () => (tree) => {
  findAndReplace(tree, [
    [/\n/g, () => ({ type: 'break' })],
  ], {
    ignore: ['code', 'inlineCode'],
  });
};
