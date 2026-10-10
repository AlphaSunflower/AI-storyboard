import { Download, FileText } from 'lucide-react';
import type { AgentAsset, AgentAttachment, AgentPage, AgentTaskView } from '../../api/agent';
import { assetUrl } from '../../config';
import { documentMimeType, documentPreviewKind } from '../../lib/documentPreview';
import { useAgentStore } from '../../stores/agentStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
import {
  assetUrls,
  documentAttachmentForAsset,
  fileNameFromAssetUrl,
} from './assetBrowserModel';
import './TaskDocumentActions.css';

const DOCUMENT_PREVIEW_KINDS = new Set([
  'docx',
  'pptx',
  'ppt',
  'markdown',
  'text',
  'json',
  'html',
  'pdf',
]);

function taskDocumentAttachments(
  task: AgentTaskView,
  assets: AgentPage<AgentAsset> | null,
): AgentAttachment[] {
  const records = assets?.records ?? [];
  const recordsById = new Map(records.map((asset) => [asset.id, asset]));
  const recordsByUrl = new Map<string, AgentAsset>();
  for (const asset of records) {
    for (const url of assetUrls(asset)) recordsByUrl.set(url, asset);
    if (asset.url) recordsByUrl.set(asset.url, asset);
  }

  const documents = new Map<string, AgentAttachment>();
  const addDocument = (asset: AgentAsset, url: string) => {
    if (!url || documents.has(url)) return;
    documents.set(url, documentAttachmentForAsset(asset, url));
  };

  for (const assetId of task.assetIds) {
    const asset = recordsById.get(assetId);
    if (!asset || asset.type !== 'document') continue;
    const urls = assetUrls(asset);
    if (urls.length) urls.forEach((url) => addDocument(asset, url));
    else if (asset.url) addDocument(asset, asset.url);
  }

  for (const url of task.resultUrls) {
    if (!url || documents.has(url)) continue;
    const asset = recordsByUrl.get(url);
    if (asset) {
      if (asset.type === 'document') addDocument(asset, url);
      continue;
    }
    if (!DOCUMENT_PREVIEW_KINDS.has(documentPreviewKind({ url }))) continue;
    const name = fileNameFromAssetUrl(url);
    documents.set(url, {
      url,
      name,
      mimeType: documentMimeType({ name, url }),
      kind: 'file',
    });
  }

  return [...documents.values()];
}

export function TaskDocumentActions({
  task,
  compact = false,
}: {
  task: AgentTaskView;
  compact?: boolean;
}) {
  const assets = useAgentStore((state) => state.assets);
  const documents = taskDocumentAttachments(task, assets);
  if (documents.length === 0) return null;

  return (
    <div className={`task-document-actions${compact ? ' is-compact' : ''}`} aria-label="任务文档产出">
      {documents.map((document) => (
        <div className="task-document-actions__item" key={document.url}>
          <button
            type="button"
            onClick={() => useDocumentPreviewStore.getState().openDocument(document)}
            aria-label={`打开 ${document.name}`}
            title={`打开 ${document.name}`}
          >
            <FileText size={13} strokeWidth={1.9} />
            <span>{document.name}</span>
          </button>
          <a
            href={assetUrl(document.url)}
            download={document.name}
            target="_blank"
            rel="noreferrer"
            aria-label={`下载 ${document.name}`}
            title={`下载 ${document.name}`}
          >
            <Download size={13} strokeWidth={1.9} />
            <span>下载</span>
          </a>
        </div>
      ))}
    </div>
  );
}
