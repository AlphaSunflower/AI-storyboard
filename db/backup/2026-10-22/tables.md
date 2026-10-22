# 表清单（2026-10-22 备份）

共 88 张表：public 65 / asset 22 / agentscope 1。列数为该表字段数量。

## schema `agentscope`（1 张）

| 表 | 列数 | 说明 |
|---|---|---|
| `agentscope_sessions` | 7 |  |

## schema `asset`（22 张）

| 表 | 列数 | 说明 |
|---|---|---|
| `flyway_schema_history` | 10 |  |
| `library_ai_tag_suppressions_archive` | 3 |  |
| `library_analysis_tasks` | 11 |  |
| `library_asset_folders` | 8 |  |
| `library_asset_tags` | 4 |  |
| `library_assets` | 28 |  |
| `library_blobs` | 12 |  |
| `library_folders` | 11 |  |
| `library_group_items` | 6 |  |
| `library_group_outputs` | 6 |  |
| `library_group_snapshot_items` | 8 |  |
| `library_group_snapshots` | 7 |  |
| `library_groups` | 8 |  |
| `library_idempotency_keys` | 6 |  |
| `library_import_items` | 16 |  |
| `library_import_jobs` | 21 |  |
| `library_media` | 9 |  |
| `library_project_links` | 4 |  |
| `library_saved_views` | 12 |  |
| `library_storage_cleanup_tasks` | 11 |  |
| `library_tags` | 6 |  |
| `library_upload_sessions` | 15 |  |

## schema `public`（65 张）

| 表 | 列数 | 说明 |
|---|---|---|
| `agent_archive_cleanups` | 5 | 已归档会话清理台账：每个会话只清一次，行随 conversations 级联删除 |
| `agent_assets` | 30 |  |
| `agent_async_tasks` | 33 |  |
| `agent_canvas_run_operations` | 5 |  |
| `agent_canvas_runs` | 19 |  |
| `agent_canvas_sessions` | 19 |  |
| `agent_checkpoints` | 20 |  |
| `agent_conversation_asset_groups` | 9 |  |
| `agent_document_index` | 12 |  |
| `agent_external_actions` | 15 |  |
| `agent_global_memories` | 4 |  |
| `agent_handoffs_archive` | 7 |  |
| `agent_loop_runs_archive` | 13 |  |
| `agent_loop_steps_archive` | 17 |  |
| `agent_media_critiques` | 14 |  |
| `agent_memories` | 12 |  |
| `agent_memory_settings` | 3 |  |
| `agent_messages` | 6 |  |
| `agent_packages` | 8 |  |
| `agent_recovery_leases` | 8 |  |
| `agent_sandbox_sessions` | 7 |  |
| `agent_subagent_runs` | 18 |  |
| `agent_task_edges_archive` | 5 |  |
| `agent_task_nodes_archive` | 15 |  |
| `agent_task_plans_archive` | 14 |  |
| `agent_timeline_manifests` | 10 |  |
| `agent_tool_invocations` | 34 |  |
| `agent_turn_events` | 6 |  |
| `agent_turns` | 11 |  |
| `agent_workflow_runs` | 12 |  |
| `agent_workflow_steps` | 17 |  |
| `agentscope_sessions` | 7 |  |
| `agentscope_store` | 5 |  |
| `asset_images_archive` | 7 |  |
| `asset_outbox` | 16 |  |
| `assets_archive` | 8 |  |
| `canvas_assets` | 15 |  |
| `canvas_execution_nodes` | 17 |  |
| `canvas_executions` | 8 |  |
| `canvas_graph_operations` | 10 |  |
| `canvas_job_events` | 5 |  |
| `canvas_jobs` | 28 |  |
| `conversations` | 13 |  |
| `flyway_schema_history` | 10 |  |
| `morrow_canvas_graphs` | 5 |  |
| `morrow_canvases` | 5 |  |
| `project_creative_contexts` | 15 |  |
| `projects` | 13 |  |
| `scene_asset_links` | 4 |  |
| `scene_assets` | 5 |  |
| `scene_material_group_snapshots` | 8 |  |
| `scene_prompts` | 18 |  |
| `scene_reference_images` | 13 |  |
| `scenes` | 13 |  |
| `scenes_backup_pre_v15_archive` | 28 |  |
| `storage_cleanup_queue` | 8 |  |
| `storyboard_image_task` | 19 |  |
| `storyboard_runs` | 20 |  |
| `storyboard_shot_changes` | 10 |  |
| `storyboard_shot_records` | 15 |  |
| `storyboard_video_queue` | 21 |  |
| `user_feedback` | 6 |  |
| `user_notifications` | 10 |  |
| `user_settings` | 7 |  |
| `users` | 9 |  |

