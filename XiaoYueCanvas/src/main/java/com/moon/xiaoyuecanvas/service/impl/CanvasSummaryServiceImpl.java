package com.moon.xiaoyuecanvas.service.impl;

import com.moon.xiaoyuecanvas.service.CanvasSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CanvasSummaryServiceImpl implements CanvasSummaryService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<CanvasSummary> byProject(String projectId) {
        return jdbcTemplate.query("""
                SELECT c.id AS canvas_id,
                       COALESCE(jsonb_array_length(
                           COALESCE(g.graph_json::jsonb -> 'nodes', '[]'::jsonb)), 0) AS node_count,
                       COALESCE(jsonb_array_length(
                           COALESCE(g.graph_json::jsonb -> 'edges', '[]'::jsonb)), 0) AS edge_count,
                       (SELECT COUNT(*) FROM public.canvas_assets a WHERE a.canvas_id = c.id) AS asset_count,
                       (SELECT a.id FROM public.canvas_assets a
                         WHERE a.canvas_id = c.id AND a.type = 'image' AND a.status = 'ready'
                         ORDER BY a.created_at ASC, a.id ASC
                         LIMIT 1) AS cover_asset_id
                  FROM public.morrow_canvases c
                  LEFT JOIN public.morrow_canvas_graphs g ON g.canvas_id = c.id
                 WHERE c.project_id = ?
                 ORDER BY c.updated_at DESC
                """, (rs, rowNum) -> new CanvasSummary(
                rs.getString("canvas_id"),
                rs.getInt("node_count"),
                rs.getInt("edge_count"),
                rs.getInt("asset_count"),
                rs.getString("cover_asset_id")), projectId);
    }

    @Override
    public ProjectSummary project(String projectId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS canvas_count,
                       COALESCE(SUM(COALESCE(jsonb_array_length(
                           COALESCE(g.graph_json::jsonb -> 'nodes', '[]'::jsonb)), 0)), 0) AS node_count,
                       COALESCE(SUM(COALESCE(jsonb_array_length(
                           COALESCE(g.graph_json::jsonb -> 'edges', '[]'::jsonb)), 0)), 0) AS edge_count,
                       COALESCE(SUM(
                           (SELECT COUNT(*) FROM public.canvas_assets a WHERE a.canvas_id = c.id)
                       ), 0) AS asset_count
                  FROM public.morrow_canvases c
                  LEFT JOIN public.morrow_canvas_graphs g ON g.canvas_id = c.id
                 WHERE c.project_id = ?
                """, (rs, rowNum) -> new ProjectSummary(
                rs.getInt("canvas_count"),
                rs.getInt("node_count"),
                rs.getInt("edge_count"),
                rs.getInt("asset_count")), projectId);
    }
}
