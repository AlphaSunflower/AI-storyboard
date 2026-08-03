"""
AI Storyboard 后端 Dify 代理 HTTP 客户端。

调用后端 POST /api/ai/dify/* 端点，携带 X-Dify-Key 认证 header。
"""
import os
import json
import time
import logging
from typing import Optional

import httpx

logger = logging.getLogger(__name__)

# 默认配置 — 可通过环境变量覆盖
BACKEND_BASE_URL = os.getenv("DIFY_MEDIA_BACKEND_URL", "http://host.docker.internal:8082")
DIFY_API_KEY = os.getenv("DIFY_MEDIA_API_KEY", "")
DIFY_KEY_HEADER = "X-Dify-Key"

# 超时配置（秒）
IMAGE_TIMEOUT = int(os.getenv("DIFY_MEDIA_IMAGE_TIMEOUT", "120"))  # 图片生成 2 分钟
VIDEO_CREATE_TIMEOUT = int(os.getenv("DIFY_MEDIA_VIDEO_CREATE_TIMEOUT", "300"))  # 视频创建+轮询 5 分钟
VIDEO_POLL_INTERVAL = int(os.getenv("DIFY_MEDIA_VIDEO_POLL_INTERVAL", "5"))  # 轮询间隔 5 秒


def _get_headers() -> dict:
    """构建请求 headers，包含 Dify API Key 认证。"""
    headers = {"Content-Type": "application/json"}
    if DIFY_API_KEY:
        headers[DIFY_KEY_HEADER] = DIFY_API_KEY
    return headers


def _build_url(path: str) -> str:
    """拼接完整的后端 URL。"""
    return f"{BACKEND_BASE_URL.rstrip('/')}/api/ai/dify/{path.lstrip('/')}"


def _parse_response(data: dict) -> dict:
    """
    解析后端 ApiResponse 格式的响应。

    后端返回格式：{ code: int, message: str, data: dict }
    code=0 表示成功（ApiResponse.ok）
    """
    code = data.get("code", -1)
    message = data.get("message", "")
    result = data.get("data", {}) if data.get("data") else {}

    if code != 0:
        raise RuntimeError(f"后端返回错误 (code={code}): {message}")

    return result


async def generate_image(
    project_id: str = "mcp-default",
    scene_id: Optional[str] = None,
    prompt: str = "",
    model: Optional[str] = None,
    size: Optional[str] = None,
    quality: Optional[str] = None,
    reference_image_urls: Optional[list] = None,
) -> dict:
    """
    调用后端 /api/ai/dify/generate-image 生成图片。

    sceneId 非空时，结果直接写入该分镜记录；为空则创建临时 Scene（兼容旧行为）。

    返回：{ imageUrl, filename }
    """
    body = {
        "projectId": project_id,
        "prompt": prompt,
    }
    if scene_id:
        body["sceneId"] = scene_id
    if model:
        body["model"] = model
    if size:
        body["size"] = size
    if quality:
        body["quality"] = quality
    if reference_image_urls:
        body["referenceImageUrls"] = reference_image_urls

    url = _build_url("generate-image")

    async with httpx.AsyncClient(timeout=IMAGE_TIMEOUT) as client:
        response = await client.post(url, json=body, headers=_get_headers())
        response.raise_for_status()
        return _parse_response(response.json())


async def generate_video(
    project_id: str = "mcp-default",
    scene_id: Optional[str] = None,
    prompt: str = "",
    model: Optional[str] = None,
    resolution: Optional[str] = None,
    size: Optional[str] = None,
    aspect_ratio: Optional[str] = None,
    duration: Optional[int] = None,
    negative_prompt: Optional[str] = None,
    reference_image_urls: Optional[list] = None,
) -> dict:
    """
    调用后端 /api/ai/dify/generate-video 生成视频。

    后端会同步等待视频生成完成（轮询最久 5 分钟），
    所以这里设置较长的超时时间。

    sceneId 非空时，结果直接写入该分镜记录；为空则创建临时 Scene（兼容旧行为）。

    返回：{ videoUrl, taskId }
    """
    body = {
        "projectId": project_id,
        "prompt": prompt,
    }
    if scene_id:
        body["sceneId"] = scene_id
    if model:
        body["model"] = model
    if resolution:
        body["resolution"] = resolution
    if size:
        body["size"] = size
    if aspect_ratio:
        body["aspectRatio"] = aspect_ratio
    if duration:
        body["duration"] = duration
    if negative_prompt:
        body["negativePrompt"] = negative_prompt
    if reference_image_urls:
        body["referenceImageUrls"] = reference_image_urls

    url = _build_url("generate-video")

    async with httpx.AsyncClient(timeout=VIDEO_CREATE_TIMEOUT) as client:
        response = await client.post(url, json=body, headers=_get_headers())
        response.raise_for_status()
        return _parse_response(response.json())
