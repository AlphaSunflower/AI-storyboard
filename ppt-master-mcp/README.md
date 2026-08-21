# PPT Master MCP Server

将 [ppt-master](https://github.com/hugohe3/ppt-master) 封装为 MCP 工具，供 LLM 调用生成 PPT。

## 架构

```
调用方 LLM（如 Moon Agent）
  │
  ├─ list_formats()          # 查看可用格式
  ├─ init_project("demo")    # 创建项目
  ├─ import_sources(...)      # 导入素材（可选）
  │
  ├─ [LLM 自己写 SVG 页面]    # 创意部分，MCP 管不了
  │
  ├─ write_svg_page(1, svg)   # 保存第 1 页
  ├─ write_svg_page(2, svg)   # 保存第 2 页
  ├─ ...
  ├─ quality_check()          # 质量检查
  └─ export_pptx()            # 导出 .pptx
```

核心思想：**MCP server 负责脚手架，LLM 负责创意**。

## 使用

### stdio 模式（推荐，Hermes/Claude 等客户端直接用）

```bash
python server.py
```

### SSE 模式（调试用）

```bash
python server.py --port 8084
```

### Hermes 配置

在 `config.yaml` 的 `mcp_servers` 下添加：

```yaml
mcp_servers:
  ppt_master:
    command: python
    args: ["E:/Desktop/AI-storyboard/ppt-master-mcp/server.py"]
    env:
      PPT_MASTER_SKILL_DIR: "C:/Users/38632/AppData/Local/hermes/skills/productivity/ppt-master"
```

## 工具列表

| 工具 | 说明 |
|------|------|
| `list_formats` | 列出可用画布格式 |
| `list_templates` | 列出品牌/风格/布局模板 |
| `init_project` | 创建新项目 |
| `import_sources` | 导入素材文件 |
| `write_svg_page` | 保存一页 SVG |
| `read_svg_page` | 读取已保存的 SVG |
| `quality_check` | SVG 质量检查 |
| `export_pptx` | 导出为 .pptx |
| `get_project_status` | 查看项目状态 |

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `PPT_MASTER_SKILL_DIR` | ppt-master skill 目录 | `~/AppData/Local/hermes/skills/productivity/ppt-master` |
