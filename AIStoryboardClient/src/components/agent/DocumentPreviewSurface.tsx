import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { FileText, LoaderCircle } from 'lucide-react';
import type { AgentAttachment } from '../../api/agent';
import {
  documentPreviewKind,
  formatDocumentText,
  loadDocumentSource,
  type DocumentPreviewKind,
} from '../../lib/documentPreview';
import { DocxPreview } from './DocxPreview';
import { PptxPreview } from './PptxPreview';
import './DocumentPreviewSurface.css';

type PreviewMode = 'docx' | 'pptx' | 'html' | 'markdown' | 'text' | 'pdf' | 'unsupported';

interface PreviewState {
  status: 'loading' | 'ready' | 'error';
  kind: DocumentPreviewKind;
  mode: PreviewMode;
  content: string;
  source?: Blob;
  pdfUrl?: string;
  error?: string;
  warnings: string[];
}

const EMPTY_STATE: PreviewState = {
  status: 'loading',
  kind: 'unsupported',
  mode: 'unsupported',
  content: '',
  warnings: [],
};

async function sanitizeHtml(html: string): Promise<string> {
  const { default: DOMPurify } = await import('dompurify');
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel'],
  });
}

async function buildPreview(
  attachment: AgentAttachment,
  signal: { cancelled: boolean },
): Promise<{ state: PreviewState; revokeUrl?: string }> {
  const kind = documentPreviewKind(attachment);
  if (kind === 'unsupported') {
    return {
      state: {
        ...EMPTY_STATE,
        status: 'error',
        kind,
        error: '该格式暂不支持浏览器端预览，可下载后使用本地应用打开',
      },
    };
  }

  const source = await loadDocumentSource(attachment.url);
  if (signal.cancelled) return { state: EMPTY_STATE };

  if (kind === 'docx' || kind === 'pptx') {
    return {
      state: {
        status: 'ready',
        kind,
        mode: kind,
        content: '',
        source,
        warnings: [],
      },
    };
  }

  if (kind === 'ppt') {
    return {
      state: {
        ...EMPTY_STATE,
        status: 'error',
        kind,
        error: '旧版 .ppt 是二进制格式，浏览器端无法可靠解析；请下载后使用 PowerPoint 打开，或另存为 .pptx。',
      },
    };
  }

  if (kind === 'pdf') {
    const pdfUrl = URL.createObjectURL(source);
    return {
      state: {
        status: 'ready',
        kind,
        mode: 'pdf',
        content: '',
        pdfUrl,
        warnings: [],
      },
      revokeUrl: pdfUrl,
    };
  }

  const content = formatDocumentText(await source.text(), kind);
  if (signal.cancelled) return { state: EMPTY_STATE };

  if (kind === 'html') {
    return {
      state: {
        status: 'ready',
        kind,
        mode: 'html',
        content: await sanitizeHtml(content),
        warnings: [],
      },
    };
  }

  return {
    state: {
      status: 'ready',
      kind,
      mode: kind === 'markdown' ? 'markdown' : 'text',
      content,
      warnings: [],
    },
  };
}

export function DocumentPreviewSurface({ attachment }: { attachment: AgentAttachment }) {
  const [state, setState] = useState<PreviewState>(EMPTY_STATE);

  useEffect(() => {
    const signal = { cancelled: false };
    let revokeUrl: string | undefined;
    setState({
      ...EMPTY_STATE,
      status: 'loading',
      kind: documentPreviewKind(attachment),
    });
    void buildPreview(attachment, signal)
      .then((result) => {
        if (signal.cancelled) {
          if (result.revokeUrl) URL.revokeObjectURL(result.revokeUrl);
          return;
        }
        revokeUrl = result.revokeUrl;
        setState(result.state);
      })
      .catch((error: unknown) => {
        if (signal.cancelled) return;
        setState({
          ...EMPTY_STATE,
          status: 'error',
          kind: documentPreviewKind(attachment),
          error: error instanceof Error ? error.message : '文档读取失败',
        });
      });
    return () => {
      signal.cancelled = true;
      if (revokeUrl) URL.revokeObjectURL(revokeUrl);
    };
  }, [attachment]);

  const canRender = state.status === 'ready';
  const modeClass = state.mode === 'docx' ? ' is-docx' : state.mode === 'pptx' ? ' is-pptx' : '';

  return (
    <div className={`document-preview__viewport${modeClass}`}>
      {state.status === 'loading' && (
        <div className="document-preview__status" role="status">
          <LoaderCircle className="is-spinning" size={24} strokeWidth={1.7} />
          <span>正在浏览器中解析文档…</span>
        </div>
      )}
      {state.status === 'error' && (
        <div className="document-preview__status is-error">
          <FileText size={28} strokeWidth={1.5} />
          <strong>暂时无法预览</strong>
          <span>{state.error}</span>
        </div>
      )}
      {canRender && state.mode === 'pdf' && state.pdfUrl && (
        <iframe
          className="document-preview__pdf"
          src={state.pdfUrl}
          title={attachment.name}
        />
      )}
      {canRender && state.mode === 'docx' && state.source && (
        <DocxPreview source={state.source} />
      )}
      {canRender && state.mode === 'pptx' && state.source && (
        <PptxPreview source={state.source} />
      )}
      {canRender && state.mode !== 'pdf' && state.mode !== 'docx' && state.mode !== 'pptx' && (
        <article className="document-preview__paper">
          {state.mode === 'html' && (
            <div
              className="document-preview__content"
              dangerouslySetInnerHTML={{ __html: state.content }}
            />
          )}
          {state.mode === 'markdown' && (
            <div className="document-preview__content">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                components={{
                  a: ({ href, children }) => (
                    <a href={href} target="_blank" rel="noreferrer">{children}</a>
                  ),
                }}
              >
                {state.content}
              </ReactMarkdown>
            </div>
          )}
          {state.mode === 'text' && (
            <pre className={`document-preview__text${state.kind === 'json' ? ' is-code' : ''}`}>
              {state.content}
            </pre>
          )}
          {state.warnings.length > 0 && (
            <div className="document-preview__warning">
              已尽量按原格式转换：{state.warnings.join('；')}
            </div>
          )}
        </article>
      )}
    </div>
  );
}
