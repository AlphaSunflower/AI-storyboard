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

export function InspectorPanel() {
  const selectedNodeId = useCanvasStore((state) => state.selectedNodeId);
  const nodes = useCanvasStore((state) => state.nodes);
  const nodeData = useCanvasStore((state) => state.nodes.find((item) => item.id === state.selectedNodeId)?.data);
  const updateNode = useCanvasStore((state) => state.updateNode);
  const duplicate = useCanvasStore((state) => state.duplicateSelected);
  const remove = useCanvasStore((state) => state.deleteSelected);
  const selectNode = useCanvasStore((state) => state.selectNode);

  const lineage = useMemo(() => (selectedNodeId ? buildLineage(nodes, selectedNodeId) : []), [nodes, selectedNodeId]);

  if (!selectedNodeId || !nodeData) return <div className="inspector-empty"><span className="empty-target"><i /></span><h3>尚未选择节点</h3><p>选择图片或文字节点后，可以编辑名称和说明。</p></div>;

  const params = nodeData.operation && Object.keys(nodeData.operation.params).length > 0
    ? nodeData.operation.params
    : null;

  return (
    <div className="inspector" key={selectedNodeId}>
      <div className="inspector-identity"><span style={{ background: nodeData.accent }} /><div><small>{nodeData.kind.toUpperCase()} NODE</small><strong>{selectedNodeId.slice(0, 22)}…</strong></div></div>
      <label className="field-label">节点名称<input defaultValue={nodeData.label} onBlur={(event) => updateNode(selectedNodeId, { label: event.target.value })} /></label>
      <label className="field-label">说明<textarea defaultValue={nodeData.prompt} rows={7} onBlur={(event) => updateNode(selectedNodeId, { prompt: event.target.value })} /></label>

      {nodeData.operation && (
        <div style={{ marginTop: 12, padding: '10px 12px', borderRadius: 8, background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)' }}>
          <small style={{ opacity: 0.6 }}>操作记录</small>
          <div style={{ marginTop: 4, fontWeight: 600 }}>{operationLabel(nodeData.operation.type)}</div>
          {nodeData.operation.at && <div style={{ fontSize: 11, opacity: 0.5 }}>{formatTime(nodeData.operation.at)}</div>}
          {params && <code style={{ display: 'block', marginTop: 6, fontSize: 11, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{JSON.stringify(params)}</code>}
        </div>
      )}

      {lineage.length > 1 && (
        <div style={{ marginTop: 12, padding: '10px 12px', borderRadius: 8, background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)' }}>
          <small style={{ opacity: 0.6 }}>血缘链</small>
          <ol style={{ listStyle: 'none', margin: '6px 0 0', padding: 0 }}>
            <li style={{ fontWeight: 600, marginBottom: 4 }}>{lineage[0].label}</li>
            {lineage.slice(1).map((step, index) => {
              const op = operationLabel(lineage[index].operationType);
              return (
                <li key={step.id} style={{ marginBottom: 3, display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span style={{ fontSize: 11, opacity: 0.5, minWidth: 18, textAlign: 'right' }}>{op ?? '←'}</span>
                  <button type="button" onClick={() => selectNode(step.id)} style={{ background: 'none', border: 'none', padding: 0, color: 'inherit', cursor: 'pointer', textDecoration: 'underline', textDecorationStyle: 'dotted' }}>{step.label}</button>
                </li>
              );
            })}
          </ol>
        </div>
      )}

      {nodeData.assetId && <div className="inspector-tip">该节点引用真实本地资产。编辑操作会创建派生版本，不会覆盖原图。</div>}
      <div className="inspector-actions"><button onClick={duplicate}><Copy size={15} /> 复制节点</button><button className="danger-action" onClick={remove}><Trash2 size={15} /> 删除</button></div>
    </div>
  );
}
