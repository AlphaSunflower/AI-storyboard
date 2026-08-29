-- Morrow 画布节点图：整幅 {nodes, edges, viewport} 快照 + revision 乐观锁。
-- 画布元数据在 morrow_canvases；本表以 canvas_id 为一画布一行（PK 即 FK）。
-- 资产二进制（Blob/变体）仍留浏览器 IndexedDB，节点 data.assetId 指向资产 key。
CREATE TABLE IF NOT EXISTS morrow_canvas_graphs (
    canvas_id  text PRIMARY KEY REFERENCES morrow_canvases(id) ON DELETE CASCADE,
    graph_json text NOT NULL,
    revision   integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_morrow_canvas_graphs_canvas ON morrow_canvas_graphs(canvas_id);
