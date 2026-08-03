"""
Dify Media MCP Server — 入口点。

通过 stdio 与 MCP 客户端（如 Hermes、Claude Desktop、Dify）通信，
注册文生图、图生图、图生视频工具。
"""
import asyncio
import logging
import sys

from mcp.server import MCPServer

from dify_media_mcp.tools.image import generate_image_tool
from dify_media_mcp.tools.video import generate_video_tool

# 日志输出到 stderr（避免破坏 stdio 协议）
logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] [%(levelname)s] %(name)s: %(message)s",
    stream=sys.stderr,
)
logger = logging.getLogger(__name__)

# 创建 MCP 服务器实例
server = MCPServer(
    name="dify-media-mcp",
    instructions=(
        "Dify Media MCP Server — 为 Dify 提供文生图、图生图、图生视频能力。\n"
        "通过 AI Storyboard 后端代理（host.docker.internal:8082）调用 Laozhang API。\n\n"
        "可用工具：\n"
        "- generate_image: 文生图/图生图\n"
        "- generate_video: 图生视频"
    ),
)


@server.tool()
async def generate_image(
    prompt: str,
    project_id: str = "mcp-default",
    scene_id: str = None,
    model: str = None,
    size: str = None,
    quality: str = None,
    reference_image_urls: list = None,
) -> str:
    """文生图/图生图 — 通过 AI Storyboard 后端代理调用 Laozhang API。

    传 scene_id 时结果直接写入该分镜（存 PostgreSQL）；不传则创建临时记录。

    Args:
        prompt: 生图提示词（必填）
        project_id: 项目 ID
        scene_id: 分镜 ID（非空时写入该分镜）
        model: AI 模型名，如 gpt-image-2、gemini-3-flash-preview
        size: 图片尺寸，如 1024x1024、1792x1024
        quality: 图片质量，如 standard、hd
        reference_image_urls: 参考图 URL 列表（图生图模式）
    """
    return await generate_image_tool(
        prompt=prompt,
        project_id=project_id,
        scene_id=scene_id,
        model=model,
        size=size,
        quality=quality,
        reference_image_urls=reference_image_urls,
    )


@server.tool()
async def generate_video(
    prompt: str,
    project_id: str = "mcp-default",
    scene_id: str = None,
    model: str = None,
    resolution: str = None,
    size: str = None,
    aspect_ratio: str = None,
    duration: int = None,
    negative_prompt: str = None,
    reference_image_urls: list = None,
) -> str:
    """图生视频 — 通过 AI Storyboard 后端代理调用 Laozhang API（同步等待，最长 5 分钟）。

    传 scene_id 时结果直接写入该分镜（存 PostgreSQL）；不传则创建临时记录。

    Args:
        prompt: 视频生成提示词（必填）
        project_id: 项目 ID
        scene_id: 分镜 ID（非空时写入该分镜）
        model: AI 模型名，如 veo-3.1-fast
        resolution: 分辨率，如 1080p
        size: 视频尺寸
        aspect_ratio: 宽高比，如 16:9、9:16
        duration: 视频时长（秒）
        negative_prompt: 负面提示词
        reference_image_urls: 参考图 URL 列表
    """
    return await generate_video_tool(
        prompt=prompt,
        project_id=project_id,
        scene_id=scene_id,
        model=model,
        resolution=resolution,
        size=size,
        aspect_ratio=aspect_ratio,
        duration=duration,
        negative_prompt=negative_prompt,
        reference_image_urls=reference_image_urls,
    )


def main():
    """CLI 入口点 — 启动 stdio MCP 服务器。"""
    logger.info("Dify Media MCP Server 启动中...")
    asyncio.run(server.run_stdio_async())


if __name__ == "__main__":
    main()
