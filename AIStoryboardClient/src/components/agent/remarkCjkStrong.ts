import { findAndReplace } from 'mdast-util-find-and-replace';
import type { Root } from 'mdast';
import type { Plugin } from 'unified';

/**
 * CommonMark rejects `**bold**` when delimiters touch CJK characters on both
 * sides. LLM responses commonly use that form, so repair it after parsing.
 */
export const remarkCjkStrong: Plugin<[], Root> = () => (tree) => {
  findAndReplace(tree, [
    [/\*\*([^*\n]+)\*\*/g, (_match, value) => ({
      type: 'strong',
      children: [{ type: 'text', value }],
    })],
  ], {
    ignore: ['code', 'inlineCode'],
  });
};
