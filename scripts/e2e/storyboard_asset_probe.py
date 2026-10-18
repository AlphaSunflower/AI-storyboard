#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""分镜资产携带 e2e 探针。

验证链路（四层）：
  1. 上传参考图 -> agent_assets.description 被视觉模型打标（「角色｜…」「场景｜…」）
  2. start_storyboard_production -> storyboard_runs.assets 非空（模型没传 asset_ids 时服务端兜底）
  3. 伪分镜/正式镜头记录 -> storyboard_shot_records.reference_asset_ids 非空
  4. 写库 -> scenes.scene_assets 有行

用法：python scripts/e2e/storyboard_asset_probe.py
输出：stdout 打印各阶段结果 + 末尾一行 JSON（token / projectId / conversationId / assetIds）
"""
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE_GW = "http://localhost:8080"
EMAIL = "e2e_1790675373_850@test.local"
PASSWORD = "E2eNewPass123!"
SEED_IMAGES_DB = "ai_storyboard"

TMP = os.path.join(os.environ.get("TEMP", "/tmp"), "storyboard_asset_probe")
os.makedirs(TMP, exist_ok=True)


def req(method, path, body=None, token=None, timeout=60, base=BASE_GW):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base + path, data=data, method=method)
    if body is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        resp = urllib.request.urlopen(r, timeout=timeout)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


def parse(raw):
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001
        return {"raw": raw[:400]}


def login():
    status, raw = req("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD})
    payload = parse(raw)
    token = (payload.get("data") or {}).get("accessToken")
    if not token:
        raise SystemExit("login failed: %s %s" % (status, raw[:300]))
    print("[1] login ok")
    return token


def create_project(token):
    name = "资产携带探针 " + uuid.uuid4().hex[:6]
    status, raw = req("POST", "/api/projects", {
        "name": name,
        "description": "e2e: storyboard asset carrying probe",
        "creationType": "video",
        "aspectRatio": "16:9",
    }, token=token)
    payload = parse(raw)
    project = payload.get("data") or {}
    if not project.get("id"):
        raise SystemExit("create project failed: %s %s" % (status, raw[:300]))
    print("[2] project created: %s (%s)" % (project["id"], name))
    return project["id"]


def create_conversation(token, project_id):
    status, raw = req("POST", "/api/agent/conversations", {"projectId": project_id}, token=token)
    payload = parse(raw)
    conv = payload.get("data") or {}
    if not conv.get("id"):
        raise SystemExit("create conversation failed: %s %s" % (status, raw[:300]))
    print("[3] conversation created: %s" % conv["id"])
    return conv["id"]


def download(url_path, target):
    url = url_path if url_path.startswith("http") else BASE_GW + url_path
    with urllib.request.urlopen(url, timeout=60) as resp, open(target, "wb") as fh:
        fh.write(resp.read())
    return os.path.getsize(target)


def upload(token, conversation_id, path, purpose):
    boundary = "----probe" + uuid.uuid4().hex
    filename = os.path.basename(path)
    ctype = mimetypes.guess_type(filename)[0] or "image/jpeg"
    with open(path, "rb") as fh:
        content = fh.read()
    parts = []
    parts.append(("--" + boundary + "\r\n").encode())
    parts.append(('Content-Disposition: form-data; name="purpose"\r\n\r\n' + purpose + "\r\n").encode())
    parts.append(("--" + boundary + "\r\n").encode())
    parts.append(('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename).encode())
    parts.append(("Content-Type: %s\r\n\r\n" % ctype).encode())
    parts.append(content)
    parts.append(("\r\n--" + boundary + "--\r\n").encode())
    body = b"".join(parts)

    url = BASE_GW + "/api/agent/upload?conversationId=" + conversation_id
    r = urllib.request.Request(url, data=body, method="POST")
    r.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
    r.add_header("Authorization", "Bearer " + token)
    try:
        resp = urllib.request.urlopen(r, timeout=120)
        payload = parse(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raise SystemExit("upload failed: %s %s" % (e.code, e.read().decode("utf-8", "replace")[:300]))
    uploaded = payload.get("data") or {}
    if not uploaded.get("assetId"):
        raise SystemExit("upload returned no assetId: %s" % payload)
    print("[4] uploaded %s -> assetId=%s" % (filename, uploaded["assetId"]))
    return uploaded["assetId"], uploaded.get("url", "")


def sse_turn(token, conversation_id, content, timeout=600):
    body_map = {
        "content": content,
        "clientRequestId": "probe-" + uuid.uuid4().hex[:12],
    }
    prefs_raw = os.environ.get("PROBE_PREFS")
    if prefs_raw:
        body_map["generationPreferences"] = json.loads(prefs_raw)
    body = json.dumps(body_map).encode()
    url = BASE_GW + "/api/agent/conversations/" + conversation_id + "/turns/stream"
    r = urllib.request.Request(url, data=body, method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    counts = {}
    terminal = None
    started = time.time()
    resp = urllib.request.urlopen(r, timeout=timeout)
    buf = b""
    while time.time() - started < timeout:
        chunk = resp.read(2048)
        if not chunk:
            break
        buf += chunk
        while b"\n\n" in buf:
            frame, buf = buf.split(b"\n\n", 1)
            event = None
            data_lines = []
            for line in frame.decode("utf-8", "replace").splitlines():
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].strip())
            if not event:
                continue
            counts[event] = counts.get(event, 0) + 1
            if event in ("turn_completed", "turn_paused", "turn_failed"):
                terminal = (event, "\n".join(data_lines)[:200])
                if event != "turn_paused":
                    resp.close()
                    return counts, terminal, time.time() - started
    return counts, terminal, time.time() - started


def main():
    token = login()
    project_id = create_project(token)
    conversation_id = create_conversation(token, project_id)

    # 复用库里已生成的真实图片（避免拿合成图喂视觉模型，测不出实际描述质量）
    status, raw = req("GET", "/api/agent/conversations/%s/assets?page=1&size=5" % conversation_id, token=token)
    seeds = json.load(open(os.path.join(TMP, "seeds.json"), "r", encoding="utf-8")) \
        if os.path.exists(os.path.join(TMP, "seeds.json")) else []
    if not seeds:
        raise SystemExit("缺少 seeds.json（两条真实图片 URL），请先生成")
    asset_ids = []
    for index, seed_url in enumerate(seeds[:2], start=1):
        target = os.path.join(TMP, "seed%d.jpg" % index)
        size = download(seed_url, target)
        print("[4.%d] downloaded seed %d: %d bytes" % (index, index, size))
        asset_id, _ = upload(token, conversation_id, target,
                             "参考素材%d" % index)
        asset_ids.append(asset_id)

    print("[5] 等待视觉打标（最多 60s）")
    time.sleep(25)

    prompt = os.environ.get("PROBE_PROMPT") or (
        "用我上传的这两张参考图做一个 4 个镜头的短视频分镜，共4个镜头，16:9。"
        "剧情：雨夜，人物走进一间旧仓库，最后停留在窗边。请直接开始分镜制作。"
    )
    print("[6] 发送 turn（可能耗时数分钟）…")
    counts, terminal, elapsed = sse_turn(token, conversation_id, prompt)
    print("[6] turn 结束 用时=%.1fs 终态=%s" % (elapsed, terminal[0] if terminal else "无"))
    print("    事件统计: %s" % json.dumps(counts, ensure_ascii=False))

    # 剧情方案任务由调用方（psql + approve 接口）接着推进，脚本只负责"上传 + 发起一轮"。
    print("PROBE_JSON " + json.dumps({
        "projectId": project_id,
        "conversationId": conversation_id,
        "assetIds": asset_ids,
        "terminal": terminal[0] if terminal else None,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
