import { useState } from 'react';
import { listNodeDefinitions, type NodeCategory } from '../../canvas/graphContracts';

/**
 * 分组节点菜单：按 NODE_DEFS 的分类（基础/文本/媒体/编排）折叠展示所有节点。
 * 不直接调 addNode，而是把选中的 kind 经 onAdd 回调交给父组件——右键菜单与节点库均可复用。
 */
export function GroupedNodeMenu({ onAdd }: { onAdd: (kind: string) => void }) {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const definitions = listNodeDefinitions();
  const categories = [...new Set(definitions.map((definition) => definition.category))] as NodeCategory[];
  const grouped = categories.map((cat) => ({ cat, items: definitions.filter((d) => d.category === cat) })).filter((g) => g.items.length);
  return (
    <div className="grouped-node-menu">
      {grouped.map(({ cat, items }) => (
        <section key={cat}>
          <button className="group-cat" onClick={() => setCollapsed((c) => ({ ...c, [cat]: !c[cat] }))}>{cat}</button>
          {!collapsed[cat] && items.map((def) => (
            <button key={def.kind} className="group-item" onClick={() => onAdd(def.kind)}>{def.title}</button>
          ))}
        </section>
      ))}
    </div>
  );
}
