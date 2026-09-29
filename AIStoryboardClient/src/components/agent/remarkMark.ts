import { findAndReplace } from 'mdast-util-find-and-replace';
import type { Root } from 'mdast';
import type { Plugin } from 'unified';

/**
 * `==highlight==` is not part of CommonMark, so react-markdown would otherwise
 * print the raw delimiters. MoonAgent is asked to use it for the highest
 * priority callouts; map the matched text onto a `mark` element (via hName)
 * so the chat renderer can style it.
 */
export const remarkMark: Plugin<[], Root> = () => (tree) => {
  findAndReplace(tree, [
    [/==([^=\n]+)==/g, (_match, value) => ({
      type: 'emphasis',
      data: { hName: 'mark' },
      children: [{ type: 'text', value }],
    })],
  ], {
    ignore: ['code', 'inlineCode'],
  });
};
