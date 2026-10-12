import { useEffect, useRef, useState } from 'react';
import { Check, Copy, Download } from 'lucide-react';
import { Tooltip, TooltipGroup } from '../common/Tooltip';
import './ChatBlockActions.css';

export type ChatBlockKind = 'table' | 'code' | 'image' | 'video';

/** 复制回调可以返回 false 表示失败（图标不切成功态），也可以什么都不返回。 */
export type ChatBlockCopyHandler = () => void | boolean | Promise<void | boolean>;

const KIND_NOUNS: Record<ChatBlockKind, string> = {
  table: '表格',
  code: '代码',
  image: '图片',
  video: '视频',
};

export interface ChatBlockActionsProps {
  kind: ChatBlockKind;
  variant?: 'light' | 'dark';
  /** 覆盖默认名词（用于 aria-label 与 tooltip，如「表格」→「复制表格」） */
  label?: string;
  onCopy: ChatBlockCopyHandler;
  onDownload: () => void;
}

/**
 * 内容块右上角的复制 / 下载操作条。
 * 显隐由父级 :hover / :focus-within 控制（触屏常显），见 ChatBlockActions.css。
 */
export function ChatBlockActions({
  kind,
  variant = 'light',
  label,
  onCopy,
  onDownload,
}: ChatBlockActionsProps) {
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<number | null>(null);
  const noun = label ?? KIND_NOUNS[kind];

  useEffect(() => () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
  }, []);

  const handleCopy = async (event: React.MouseEvent) => {
    event.stopPropagation();
    const result = await onCopy();
    if (result === false) return;
    setCopied(true);
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null;
      setCopied(false);
    }, 1400);
  };

  return (
    // 根节点用 span：操作条会出现在 markdown 的 <p> 里（图片块），div 会被 React 判定为非法嵌套
    <span
      className={`chat-block-actions chat-block-actions--${variant}`}
      data-annotation-ui
      data-kind={kind}
    >
      <TooltipGroup>
        <Tooltip label={copied ? '已复制' : `复制${noun}`}>
          <button
            type="button"
            className="chat-block-actions__button"
            aria-label={copied ? '已复制' : `复制${noun}`}
            onClick={(event) => { void handleCopy(event); }}
          >
            {copied
              ? <Check size={13} strokeWidth={2} />
              : <Copy size={13} strokeWidth={1.8} />}
          </button>
        </Tooltip>
        <Tooltip label={`下载${noun}`}>
          <button
            type="button"
            className="chat-block-actions__button"
            aria-label={`下载${noun}`}
            onClick={(event) => {
              event.stopPropagation();
              onDownload();
            }}
          >
            <Download size={13} strokeWidth={1.8} />
          </button>
        </Tooltip>
      </TooltipGroup>
    </span>
  );
}
