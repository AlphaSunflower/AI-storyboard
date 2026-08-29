import { useState } from 'react';
import { NODE_CATEGORIES, NODE_DEFS, listMenuNodes, type NodeCategory } from '../../domain/nodeRegistry';

/**
 * 分组节点菜单：按 NODE_DEFS 的分类（基础/文本/媒体/编排）折叠展示所有节点。
 * 不直接调 addNode，而是把选中的 kind 经 onAdd 回调交给父组件——右键菜单与节点库均可复用。
 */
export function GroupedNodeMenu({ onAdd }: { onAdd: (kind: string) => void }) {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const grouped = NODE_CATEGORIES.map((cat) => ({ cat, items: listMenuNodes().filter((d) => d.category === cat) })).filter((g) => g.items.length);
  return (
    <div className="grouped-node-menu">
      {grouped.map(({ cat, items }) => (
        <section key={cat}>
          <button className="group-cat" onClick={() => setCollapsed((c) => ({ ...c, [cat]: !c[cat] }))}>{cat}</button>
          {!collapsed[cat] && items.map((def) => (
            <button key={def.type} className="group-item" onClick={() => onAdd(def.type)}>{def.menuLabel}</button>
          ))}
        </section>
      ))}
    </div>
  );
}
