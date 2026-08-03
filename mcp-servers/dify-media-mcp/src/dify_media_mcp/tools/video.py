"""
generate_video 工具 — 图生视频。

调用 AI Storyboard 后端 Dify 代理端点 POST /api/ai/dify/generate-video。
后端会同步等待视频生成完成（轮询最长 5 分钟）。
"""
import logging
from typing import Optional

from dify_media_mcp.lib.client import generate_video as _generate_video

logger = logging.getLogger(__name__)

# 工具描述 — AI 助手选择工具的关键依据
TOOL_DESCRIPTION = (
    "生成视频。基于图片/参考图生成视频。（TODO: 未来支持文生视频）\n"
    "返回 { videoUrl, taskId }，videoUrl 为可访问的视频 URL。\n"
    "注意：视频生成是异步任务，后端会轮询等待完成（最长 5 分钟），\n"
    "请耐心等待，不要重复提交相同的请求。\n"
    "支持的模型：veo-3.1-fast 等。"
)


async def generate_video_tool(
    prompt: str,
    project_id: str = "mcp-default",
    scene_id: Optional[str] = None,
    model: Optional[str] = None,
    resolution: Optional[str] = None,
    size: Optional[str] = None,
    aspect_ratio: Optional[str] = None,
    duration: Optional[int] = None,
    negative_prompt: Optional[str] = None,
    reference_image_urls: Optional[list] = None,
) -> str:
    """
    生成视频的 MCP 工具处理函数。

    Args:
    prompt: 视频生成提示词（必填）
    project_id: 项目 ID（用于后端关联）
    scene_id: 分镜 ID（非空时结果写入该分镜记录，为空创建临时记录）
        model: AI 模型名，如 veo-3.1-fast
        resolution: 分辨率，如 "1080p"
        size: 视频尺寸
        aspect_ratio: 宽高比，如 "16:9"、"9:16"
        duration: 视频时长（秒）
        negative_prompt: 负面提示词
        reference_image_urls: 参考图 URL 列表

    Returns:
        文本描述：成功时包含 videoUrl，失败时包含错误信息。
    """
    try:
        logger.info(
            f"开始生成视频: prompt={prompt[:100]}..., "
            f"model={model}, aspect_ratio={aspect_ratio}"
        )
        result = await _generate_video(
            project_id=project_id,
            scene_id=scene_id,
            prompt=prompt,
            model=model,
            resolution=resolution,
            size=size,
            aspect_ratio=aspect_ratio,
            duration=duration,
            negative_prompt=negative_prompt,
            reference_image_urls=reference_image_urls,
        )
        logger.info(f"视频生成成功: taskId={result.get('taskId')}")
        return (
            f"视频生成成功！\n"
            f"- 视频 URL: {result.get('videoUrl', 'N/A')}\n"
            f"- 任务 ID: {result.get('taskId', 'N/A')}"
        )
    except Exception as e:
        logger.error(f"视频生成失败: {e}")
        return f"视频生成失败: {str(e)}"
