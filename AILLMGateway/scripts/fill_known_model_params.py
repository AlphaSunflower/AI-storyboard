#!/usr/bin/env python3
"""Fill model_params.params_schema from information already present in the gateway.

Sources of truth:
- model_params fixed columns: sizes / n / quality / style / durations / resolutions /
  aspect_ratios / ref_images_max / context_window
- model_route + channel: channel type (openai_compatible / gemini / minimax / ollama)
- explicit profiles only for models whose response shape / capability is already known

It does NOT invent unsupported vendor fields. Models whose type is obviously wrong
(tts mis-typed as video, qwen text model mis-typed as video) are reported and skipped.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("GATEWAY_BASE", "http://127.0.0.1:8083")
USER = os.environ.get("GATEWAY_ADMIN_USER", "admin")
PASSWORD = os.environ.get("GATEWAY_ADMIN_PASSWORD", "admin-init-9d2d14c37360be40")

SKIP_WRONG_TYPE = {
    "mimo-v2.5-tts-voicedesign",  # TTS model, currently typed as video
    "qwen2.5:7b",                 # Ollama text model, currently typed as video
}
FORCE = {
    "doubao-seedream-5-0-flash-260915",
    "gemini-3-pro-image-preview",
    "gpt-image-2",
    "image-01",
    "MiniMax-H3",
    "veo-3.1-fast-generate-preview",
}


def call(path: str, data=None, token=None, method=None):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE + path, data=body, headers=headers,
        method=method or ("POST" if data is not None else "GET"),
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"{path} -> HTTP {e.code}: {detail}") from e


def csv(value):
    if not value:
        return []
    if isinstance(value, list):
        return [str(v).strip() for v in value if str(v).strip()]
    return [s.strip() for s in str(value).split(",") if s.strip()]


def num(value, default=None):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def request_item(key, label, type_, **kw):
    item = {"key": key, "label": label, "type": type_}
    item.update({k: v for k, v in kw.items() if v is not None})
    return item


def image_spec(row, modes, response=True):
    sizes = csv(row.get("sizes"))
    qualities = csv(row.get("qualities"))
    styles = csv(row.get("styles"))
    aspects = csv(row.get("aspectRatios"))
    n_min = num(row.get("nMin"), 1)
    n_max = num(row.get("nMax"), 1)
    n_def = num(row.get("nDefault"), n_min)
    req = []
    if sizes:
        req.append(request_item("size", "尺寸", "enum", options=sizes,
                                default=row.get("sizeDefault") or sizes[0], injectDefault=True))
    if aspects:
        req.append(request_item("aspect_ratio", "画幅", "enum", options=aspects,
                                default=row.get("aspectRatioDefault") or aspects[0], injectDefault=True))
    req.append(request_item("n", "生成数量", "range", min=n_min, max=max(n_min, n_max), default=n_def))
    if qualities:
        req.append(request_item("quality", "质量", "enum", options=qualities,
                                default=row.get("qualityDefault") if row.get("qualityDefault") in qualities else qualities[0]))
    if styles:
        req.append(request_item("style", "风格", "enum", options=styles,
                                default=row.get("styleDefault") if row.get("styleDefault") in styles else styles[0]))
    if "image-to-image" in modes:
        max_items = num(row.get("refImagesMax"), 1)
        req.append(request_item("image", "参考图", "string", role="image_upload",
                                cardinality="multi" if max_items and max_items > 1 else "single",
                                maxItems=max_items if max_items and max_items > 1 else None,
                                visibleWhen={"mode": "image-to-image"},
                                requiredWhen={"mode": "image-to-image"}))
    spec = {
        "schemaVersion": 2,
        "protocol": "openai_json",
        "promptKey": "prompt",
        "modes": modes,
        "request": req,
    }
    if response:
        spec["response"] = {"sync": {
            "dataPath": "data", "mediaField": "b64_json", "fallbackField": "url",
            "stripDataUri": True,
        }}
    return spec


def video_spec(row, modes):
    durations = csv(row.get("durations"))
    resolutions = csv(row.get("resolutions"))
    aspects = csv(row.get("aspectRatios"))
    req = []
    if durations:
        vals = [num(x) for x in durations]
        vals = [v for v in vals if v is not None]
        if vals:
            req.append(request_item("duration", "时长（秒）", "range",
                                    min=min(vals), max=max(vals),
                                    default=num(row.get("durationDefault"), min(vals)),
                                    injectDefault=True))
    if resolutions:
        req.append(request_item("resolution", "分辨率", "enum", options=resolutions,
                                default=row.get("resolutionDefault") or resolutions[0],
                                injectDefault=True))
    if aspects:
        req.append(request_item("aspect_ratio", "画幅", "enum", options=aspects,
                                default=row.get("aspectRatioDefault") or aspects[0],
                                injectDefault=True))
    if "image-to-video" in modes:
        # VideoGatewayServiceImpl.toCreateInput reads imageUrl, not image.
        req.append(request_item("imageUrl", "首帧图", "string", role="image_upload",
                                cardinality="single", visibleWhen={"mode": "image-to-video"},
                                requiredWhen={"mode": "image-to-video"}))
    if "reference-to-video" in modes:
        req.append(request_item("referenceImages", "参考图", "array", role="image_upload",
                                cardinality="multi",
                                maxItems=num(row.get("refImagesMax"), 3) or 3,
                                visibleWhen={"mode": "reference-to-video"}))
    return {
        "schemaVersion": 2,
        "protocol": "openai_json",
        "promptKey": "prompt",
        "modes": modes,
        "request": req,
        "response": {"async": {
            "taskIdPath": "task_id",
            "statusPath": "status",
            "stateMap": {
                "PENDING": "queued", "queued": "queued",
                "PROCESSING": "running", "running": "running", "processing": "running",
                "SUCCESS": "succeeded", "succeeded": "succeeded", "success": "succeeded",
                "FAIL": "failed", "failed": "failed", "fail": "failed",
            },
            "mediaPath": "video_url",
            "mediaMode": "media_url",
            "pollIntervalSec": 5,
            "timeoutSec": 600,
        }},
    }


def text_spec(row, vision, channel_type):
    ollama = channel_type == "ollama"
    req = [
        request_item("temperature", "温度", "range", min=0, max=2, default=0.7),
        request_item("top_p", "Top P", "range", min=0, max=1, default=1),
    ]
    req.append(request_item("num_predict" if ollama else "max_tokens",
                            "最大输出 Token", "number"))
    return {
        "schemaVersion": 2,
        "protocol": "openai_json",
        "promptKey": "prompt",
        "modes": ["vision"] if vision else ["chat"],
        "request": req,
    }


def explicit_spec(model, row):
    if model == "doubao-seedream-5-0-flash-260915":
        spec = image_spec(row, ["text-to-image", "image-to-image"])
        spec["request"] = [x for x in spec["request"] if x["key"] != "n"]
        spec["request"] += [
            request_item("watermark", "水印", "bool", default=False, injectDefault=True, show=False),
            request_item("response_format", "响应格式", "enum",
                         options=["url", "b64_json"], default="url", injectDefault=True, show=False),
        ]
        return spec
    if model == "gpt-image-2":
        spec = image_spec(row, ["text-to-image", "image-to-image"])
        # gpt-image uses size, not aspect_ratio; the aspect column is generic/stale.
        spec["request"] = [x for x in spec["request"] if x["key"] != "aspect_ratio"]
        # gpt-image quality options are the known OpenAI set; hd comes from the existing column.
        for item in spec["request"]:
            if item["key"] == "quality":
                item["options"] = ["low", "medium", "high", "auto", "hd"]
                item["default"] = row.get("qualityDefault") or "high"
            if item["key"] == "size":
                item["options"] = ["1024x1024", "1536x1024", "1024x1536"]
                item["default"] = row.get("sizeDefault") or "1024x1024"
        return spec
    if model == "image-01":
        spec = image_spec(row, ["text-to-image"])
        spec["request"].append(request_item("response_format", "响应格式", "enum",
                                            options=["url", "b64_json"], default="url",
                                            injectDefault=True, show=False))
        spec["request"].append(request_item("prompt_optimizer", "提示词优化", "bool", default=True))
        return spec
    if model == "gemini-3-pro-image-preview":
        return image_spec(row, ["text-to-image"])
    if model == "MiniMax-H3":
        spec = video_spec(row, ["text-to-video", "image-to-video", "reference-to-video"])
        spec["response"]["async"].update({
            "taskIdPath": "task_id",
            "statusPath": "task.status",
            "mediaPath": "task.content.url",
            "mediaMode": "media_url",
        })
        spec["request"] += [
            request_item("prompt_optimizer", "提示词优化", "bool", default=True),
            request_item("fast_pretreatment", "快速预处理", "bool", default=True),
        ]
        return spec
    if model == "veo-3.1-fast-generate-preview":
        spec = video_spec(row, ["text-to-video", "image-to-video"])
        return spec
    return None


def main():
    login = call("/admin/login", {"username": USER, "password": PASSWORD})
    token = login["data"]["accessToken"]
    rows = call("/admin/model-params/all", token=token)["data"]
    routes = call("/admin/routes", token=token)["data"]

    channel_types = {}
    for r in routes:
        channel_types.setdefault((r.get("modelName"), r.get("type")), set()).add(
            str(r.get("channelId")))
    channel_info = {str(c["id"]): c for c in call("/admin/channels", token=token)["data"]}

    # Remove probe routes created during development.
    for r in routes:
        name = r.get("modelName") or ""
        if name.startswith("__"):
            try:
                call(f"/admin/routes/{r['id']}", token=token, method="DELETE")
                print(f"cleanup route {name} / {r.get('type')}")
            except Exception as e:  # noqa: BLE001
                print(f"cleanup failed {name}: {e}", file=sys.stderr)

    updated = 0
    skipped = []
    for row in rows:
        model = row.get("modelName")
        typ = row.get("type")
        if model in SKIP_WRONG_TYPE:
            skipped.append((model, typ, "类型与模型名称不符，需人工确认"))
            continue
        existing = row.get("paramsSchema")
        if existing and model not in FORCE:
            if typ in ("text", "vision"):
                patch = {}
                if row.get("temperature") is None:
                    patch["temperature"] = 0.7
                if row.get("topP") is None:
                    patch["topP"] = 1
                if patch:
                    call("/admin/model-params", {
                        "modelName": model, "type": typ, **patch,
                    }, token=token, method="PUT")
            continue

        if typ == "image":
            spec = explicit_spec(model, row) or image_spec(row, ["text-to-image"])
        elif typ == "video":
            spec = explicit_spec(model, row) or video_spec(row, ["text-to-video", "image-to-video"])
        elif typ in ("text", "vision"):
            ctypes = channel_types.get((model, typ), set())
            ctype = channel_info.get(next(iter(ctypes)), {}).get("type") if ctypes else None
            spec = text_spec(row, typ == "vision", ctype)
        else:
            skipped.append((model, typ, "未知类型"))
            continue

        schema = json.dumps(spec, ensure_ascii=False)
        check = call("/admin/model-params/validate", {"paramsSchema": schema}, token=token)["data"]
        if not check.get("valid"):
            skipped.append((model, typ, "校验失败: " + json.dumps(check.get("errors"), ensure_ascii=False)))
            continue
        call("/admin/model-params", {
            "modelName": model, "type": typ, "paramsSchema": schema,
        }, token=token, method="PUT")
        updated += 1
        print(f"filled {model} / {typ}: {len(spec.get('request', []))} params")

        # Keep the legacy fixed columns in sync with the schema defaults we just wrote.
        if typ in ("text", "vision"):
            patch = {}
            if row.get("temperature") is None:
                patch["temperature"] = 0.7
            if row.get("topP") is None:
                patch["topP"] = 1
            if patch:
                call("/admin/model-params", {
                    "modelName": model, "type": typ, **patch,
                }, token=token, method="PUT")

    print(f"\nupdated={updated}")
    for model, typ, reason in skipped:
        print(f"skipped {model} / {typ}: {reason}")


if __name__ == "__main__":
    main()
