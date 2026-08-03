# Dify Media MCP Server

为 Dify 提供文生图、图生图、图生视频能力的 MCP 服务器，通过 AI Storyboard 后端代理调用 Laozhang API。

## 工具

| 工具名 | 描述 | 超时 |
|--------|------|------|
| `generate_image` | 文生图/图生图 | ~120s |
| `generate_video` | 图生视频（异步轮询） | ~300s |

## 安装

```bash
cd mcp-servers/dify-media-mcp
pip install -e .
```

或使用 uv：

```bash
uv pip install -e .
```

## 环境变量

复制 `.env.example` 为 `.env` 并配置：

```bash
cp .env.example .env
```

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DIFY_MEDIA_BACKEND_URL` | 后端地址 | `http://host.docker.internal:8082` |
| `DIFY_MEDIA_API_KEY` | Dify API Key | 空 |
| `DIFY_MEDIA_IMAGE_TIMEOUT` | 图片超时（秒） | 120 |
| `DIFY_MEDIA_VIDEO_CREATE_TIMEOUT` | 视频超时（秒） | 300 |

## 调试

```bash
# 启动服务器（stdio 模式）
python -m dify_media_mcp.server

# 或运行时设置环境变量
DIFY_MEDIA_BACKEND_URL=http://localhost:8082 DIFY_MEDIA_API_KEY=test-key python -m dify_media_mcp.server
```

## 架构

```
Dify 工作流 → MCP 客户端 → dify-media-mcp (stdio)
                                ↓ HTTP POST
                        AI Storyboard 后端
                        /api/ai/dify/generate-image
                        /api/ai/dify/generate-video
                                ↓
                        Laozhang API (api2.laozhang.ai)
                                ↓
                        图片/视频 → 下载到 uploads/ → 返回 URL
```

## Hermes 配置

```json
{
  "mcpServers": {
    "dify-media": {
      "command": "python",
      "args": ["-m", "dify_media_mcp.server"],
      "env": {
        "DIFY_MEDIA_BACKEND_URL": "http://host.docker.internal:8082",
        "DIFY_MEDIA_API_KEY": "your-key"
      }
    }
  }
}
```
