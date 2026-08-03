"""
generate_image 工具 — 文生图 / 图生图。

调用 AI Storyboard 后端 Dify 代理端点 POST /api/ai/dify/generate-image。
支持纯文本生图、参考图生图两种模式。
"""
import logging
from typing import Optional

from dify_media_mcp.lib.client import generate_image as _generate_image

logger = logging.getLogger(__name__)

# 工具描述 — AI 助手选择工具的关键依据
TOOL_DESCRIPTION = (
    "生成/编辑图片。支持两种模式：\n"
    "1. 文生图：只提供 prompt，不传 referenceImageUrls\n"
    "2. 图生图：提供 prompt + referenceImageUrls（参考图 URL 列表）\n"
    "返回 { imageUrl, filename }，imageUrl 为可访问的图片 URL。\n"
    "支持的模型：gpt-image-2、gemini-3-flash-preview 等。\n"
    "注意：生成可能耗时 30-120 秒。"
)


async def generate_image_tool(
    prompt: str,
    project_id: str = "mcp-default",
    scene_id: Optional[str] = None,
    model: Optional[str] = None,
    size: Optional[str] = None,
    quality: Optional[str] = None,
    reference_image_urls: Optional[list] = None,
) -> str:
    """
    生成图片的 MCP 工具处理函数。

    Args:
        prompt: 生图提示词（必填）
        project_id: 项目 ID（用于后端关联）
        scene_id: 分镜 ID（非空时结果写入该分镜记录，为空创建临时记录）
        model: AI 模型名，如 gpt-image-2、gemini-3-flash-preview
        size: 图片尺寸，如 "1024x1024"、"1792x1024"
        quality: 图片质量，如 "standard"、"hd"
        reference_image_urls: 参考图 URL 列表（图生图模式）

    Returns:
        JSON 字符串：{ success: true, imageUrl: "...", filename: "..." }
    """
    try:
        logger.info(f"开始生成图片: prompt={prompt[:100]}..., sceneId={scene_id}, model={model}")
        result = await _generate_image(
            project_id=project_id,
            scene_id=scene_id,
            prompt=prompt,
            model=model,
            size=size,
            quality=quality,
            reference_image_urls=reference_image_urls,
        )
        logger.info(f"图片生成成功: {result.get('filename')}")
        return (
            f"图片生成成功！\n"
            f"- 图片 URL: {result.get('imageUrl', 'N/A')}\n"
            f"- 文件名: {result.get('filename', 'N/A')}"
        )
    except Exception as e:
        logger.error(f"图片生成失败: {e}")
        return f"图片生成失败: {str(e)}"
