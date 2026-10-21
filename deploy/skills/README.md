# 沙箱按需技能包

这个目录存放**不常驻**的可选技能包，由 `deploy/docker/sandbox/Dockerfile` 在构建时解压到 runner 镜像的
只读目录 `/opt/moon-skills/<skill>`。技能包不会被挂进任何会话工作区；只有会话显式引用该技能时，
MoonAgent 才调 `POST /internal/sandbox/{conversationId}/skills/<skill>/materialize`，
由 `SandboxSkillService` 复制到 `/workspaces/{conversationId}/skills/<skill>`。

| 文件 | 技能 id | 说明 |
|------|---------|------|
| `ppt-master-skill-v6.7.2.zip` | `ppt-master` | 上游 [ppt-master](https://github.com/hugohe3/ppt-master) v6.7.2（MIT），生成/重构/美化可编辑 PPTX。约 22MB / 13k 文件。 |

## 更新技能包

1. 下载新版本 zip，替换本目录文件并同步改 `Dockerfile` 里的 `PPT_MASTER_BUNDLE` 默认值。
2. 重新构建 runner：`docker compose -f deploy/docker/sandbox/docker-compose.yml build sandbox-runner`，
   再用实际运行它的 compose 项目重建容器（开发机目前是 `deploy/stack/docker-compose.yml`）。
3. 物化缓存键是「镜像版本 + 上游 `SKILL.md` 的 mtime/size」，镜像重建后首个引用会自动重拷；
   必要时改 `MOON_SANDBOX_SKILL_BUNDLE_VERSION` 强制失效。

## 注意

- **不要改压缩包内 `SKILL.md` 的 frontmatter**：`scripts/attribution_guard.py` 会校验
  copyright / license / official_repository / sponsors 和 gate 标记，改了就 fail-closed（退出码 78）。
- 根 `.dockerignore` 必须放行这个 zip，否则构建报 `"/deploy/skills/...zip": not found`。
- 压缩包自带 `requirements.txt`；核心依赖已在 sandbox Dockerfile 预装，PyMuPDF（AGPL）默认不装。
