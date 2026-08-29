import { useMemo } from 'react';
import { Copy, Trash2 } from 'lucide-react';
import { operationLabel } from '../canvas/operationLabels';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasNode, ImageOperation } from '../types';

interface LineageStep {
  id: string;
  label: string;
  operationType?: ImageOperation['type'];
}

// 递归 derivedFrom，构建从当前节点一路回溯到源头的血缘链。
const buildLineage = (nodes: CanvasNode[], startId: string): LineageStep[] => {
  const chain: LineageStep[] = [];
  const visited = new Set<string>();
  let current = nodes.find((node) => node.id === startId);
  while (current && !visited.has(current.id)) {
    visited.add(current.id);
    chain.push({ id: current.id, label: current.data.label, operationType: current.data.operation?.type });
    const sourceId = current.data.derivedFrom;
    current = sourceId ? nodes.find((node) => node.id === sourceId) : undefined;
  }
  return chain;
};

const formatTime = (at?: number) => (at ? new Date(at).toLocaleString('zh-CN', { hour12: false }) : undefined);

const fieldInput = 'w-full rounded-lg border border-[var(--border-dark)] bg-[rgba(19,34,30,0.55)] px-3 text-foreground outline-none transition focus:border-[var(--acid)] focus:ring-2 focus:ring-[rgba(215,255,84,0.18)]';

export function InspectorPanel() {
  const selectedNodeId = useCanvasStore((state) => state.selectedNodeId);
  const nodes = useCanvasStore((state) => state.nodes);
  const nodeData = useCanvasStore((state) => state.nodes.find((item) => item.id === state.selectedNodeId)?.data);
  const updateNode = useCanvasStore((state) => state.updateNode);
  const duplicate = useCanvasStore((state) => state.duplicateSelected);
  const remove = useCanvasStore((state) => state.deleteSelected);
  const selectNode = useCanvasStore((state) => state.selectNode);

  const lineage = useMemo(() => (selectedNodeId ? buildLineage(nodes, selectedNodeId) : []), [nodes, selectedNodeId]);

  if (!selectedNodeId || !nodeData) {
    return (
      <div className="grid h-full place-items-center gap-2 p-6 text-center">
        <span className="h-8 w-8 rounded-full border border-[var(--border-dark)] bg-[rgba(215,255,84,0.06)]" />
        <h3 className="text-[15px] font-semibold text-foreground">尚未选择节点</h3>
        <p className="max-w-[240px] text-[13px] leading-relaxed text-muted-foreground">选择图片或文字节点后，可以编辑名称和说明。</p>
      </div>
    );
  }

  const params = nodeData.operation && Object.keys(nodeData.operation.params).length > 0
    ? nodeData.operation.params
    : null;

  return (
    <div className="flex flex-col gap-3 p-4" key={selectedNodeId}>
      <div className="flex items-center gap-3 rounded-xl border border-[var(--border-dark)] bg-[rgba(215,255,84,0.05)] p-3">
        <span className="h-3 w-3 shrink-0 rounded-full" style={{ background: nodeData.accent }} />
        <div className="min-w-0">
          <small className="block font-mono text-[10px] tracking-[0.12em] text-muted-foreground">{nodeData.kind.toUpperCase()} NODE</small>
          <strong className="block truncate text-sm text-foreground">{selectedNodeId.slice(0, 22)}…</strong>
        </div>
      </div>

      <label className="flex flex-col gap-1.5 text-[13px] text-muted-foreground">节点名称
        <input defaultValue={nodeData.label} onBlur={(event) => updateNode(selectedNodeId, { label: event.target.value })} className={`h-9 ${fieldInput}`} />
      </label>
      <label className="flex flex-col gap-1.5 text-[13px] text-muted-foreground">说明
        <textarea defaultValue={nodeData.prompt} rows={7} onBlur={(event) => updateNode(selectedNodeId, { prompt: event.target.value })} className={`${fieldInput} p-3`} />
      </label>

      {nodeData.operation && (
        <div className="rounded-xl border border-[var(--border-dark)] bg-[rgba(215,255,84,0.04)] p-3">
          <small className="font-mono text-[10px] tracking-[0.08em] text-muted-foreground">操作记录</small>
          <div className="mt-1 font-medium text-foreground">{operationLabel(nodeData.operation.type)}</div>
          {nodeData.operation.at && <div className="mt-0.5 text-[11px] text-muted-foreground">{formatTime(nodeData.operation.at)}</div>}
          {params && <code className="mt-2 block whitespace-pre-wrap break-all text-[11px] leading-relaxed text-muted-foreground">{JSON.stringify(params)}</code>}
        </div>
      )}

      {lineage.length > 1 && (
        <div className="rounded-xl border border-[var(--border-dark)] bg-[rgba(215,255,84,0.04)] p-3">
          <small className="font-mono text-[10px] tracking-[0.08em] text-muted-foreground">血缘链</small>
          <ol className="mt-2 list-none space-y-1.5 p-0">
            <li className="font-medium text-foreground">{lineage[0].label}</li>
            {lineage.slice(1).map((step, index) => {
              const op = operationLabel(lineage[index].operationType);
              return (
                <li key={step.id} className="flex items-center gap-2">
                  <span className="min-w-[18px] text-right font-mono text-[11px] text-muted-foreground">{op ?? '←'}</span>
                  <button type="button" onClick={() => selectNode(step.id)} className="cursor-pointer border-0 border-b border-dotted border-[var(--border-dark)] bg-transparent p-0 text-[13px] text-acid">{step.label}</button>
                </li>
              );
            })}
          </ol>
        </div>
      )}

      {nodeData.assetId && <p className="text-xs leading-relaxed text-muted-foreground">该节点引用真实本地资产。编辑操作会创建派生版本，不会覆盖原图。</p>}
      <div className="flex gap-2">
        <button onClick={duplicate} className="flex h-9 flex-1 items-center justify-center gap-2 rounded-lg bg-acid font-medium text-ink transition hover:brightness-105"><Copy size={15} /> 复制节点</button>
        <button onClick={remove} className="flex h-9 flex-1 items-center justify-center gap-2 rounded-lg border border-[rgba(255,107,94,0.4)] bg-[rgba(255,107,94,0.1)] font-medium text-destructive transition hover:bg-[rgba(255,107,94,0.18)]"><Trash2 size={15} /> 删除</button>
      </div>
    </div>
  );
}
