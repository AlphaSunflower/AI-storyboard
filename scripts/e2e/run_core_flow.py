#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B4 主链路 E2E：项目/分镜/资产/文件 CRUD + AI 生成 + 越权验证（审计 P0/P1 实证）。
- 正常链路：userA 建项目→分镜→参考图→资产→文件上传下载→项目状态
- 越权实证（预期当前可越权，证实审计发现）：
  * P0-1 userB 改/删/插入 userA 的分镜
  * P1-2 直连 8084 伪造 X-User-Id 头
  * P1-3 直连 8082 带默认 internal.secret 打 /api/internal
"""
import base64
import sys
import time

sys.path.insert(0, "scripts/e2e")
from common import req, parse, check, load_creds, BASE_GW, BASE_BE, BASE_MA

# 1x1 红色 PNG
PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def multipart(fields, files, boundary="----e2eboundary42"):
    """构造 multipart/form-data body: fields={name:value}, files=[(name,filename,content,ctype)]"""
    body = b""
    for k, v in fields.items():
        body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    for name, fname, content, ctype in files:
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; "
                 f"filename=\"{fname}\"\r\nContent-Type: {ctype}\r\n\r\n").encode() + content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def mp_req(method, path, fields=None, files=None, token=None, base=BASE_GW, timeout=60):
    import urllib.request, urllib.error
    body, ctype = multipart(fields or {}, files or [])
    r = urllib.request.Request(base + path, data=body, method=method)
    r.add_header("Content-Type", ctype)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        resp = urllib.request.urlopen(r, timeout=timeout)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)


def main():
    c = load_creds()
    ta, tb = c["userA"]["accessToken"], c["userB"]["accessToken"]
    fails = 0

    def T(label, cond, detail=""):
        nonlocal fails
        if not check(label, cond, detail):
            fails += 1

    print("\n== B4.1 项目 CRUD（userA）==")
    st, raw = req("POST", "/api/projects", {"name": f"E2E项目{int(time.time())}"}, token=ta)
    d = parse(raw); pid = (d.get("data") or {}).get("id")
    T("建项目", st == 200 and d.get("code") == 200 and pid, f"{st} {raw[:120]}")
    st, raw = req("GET", "/api/projects", token=ta)
    T("项目列表", st == 200 and any(p.get("id") == pid for p in (parse(raw).get("data") or [])), f"{st}")
    st, raw = req("GET", f"/api/projects/{pid}", token=ta)
    T("项目详情", st == 200 and (parse(raw).get("data") or {}).get("id") == pid, f"{st}")
    st, raw = req("GET", "/api/projects/draft", token=ta)
    T("draft 查询", st == 200, f"{st}")

    print("\n== B4.2 分镜 CRUD + 参考素材 ==")
    st, raw = req("POST", f"/api/projects/{pid}/scenes",
                  {"scriptContent": "场景一 开场", "imagePrompt": "黄昏城市", "videoPrompt": "镜头推进"}, token=ta)
    d = parse(raw); sid = (d.get("data") or {}).get("id")
    T("加分镜", st == 200 and sid, f"{st} {raw[:120]}")
    st, raw = req("PUT", f"/api/scenes/{sid}", {"scriptContent": "场景一 修改后"}, token=ta)
    T("改分镜", st == 200 and (parse(raw).get("data") or {}).get("scriptContent") == "场景一 修改后", f"{st} {raw[:120]}")
    st, raw = mp_req("POST", f"/api/scenes/{sid}/references",
                  {"type": "image", "purpose": "参考图"}, files=[("file", "ref.png", PNG_1PX, "image/png")],
                  token=ta)
    ref = parse(raw).get("data") or {}
    T("上传参考图", st == 200 and ref.get("url"), f"{st} {raw[:120]}")
    st, raw = req("GET", f"/api/scenes/{sid}/references", token=ta)
    T("参考图列表", st == 200 and len(parse(raw).get("data") or []) == 1, f"{st}")
    st, raw = req("DELETE", f"/api/scenes/references/{ref.get('id')}", token=ta)
    T("删参考图", st == 200, f"{st}")
    st, raw = req("DELETE", f"/api/scenes/{sid}", token=ta)
    T("删分镜", st == 200, f"{st}")

    print("\n== B4.3 越权实证 P0-1：userB 操作 userA 的分镜（预期可越权=证实）==")
    st, raw = req("POST", f"/api/projects/{pid}/scenes", {"scriptContent": "A的分镜B来插"}, token=tb)
    sid2 = (parse(raw).get("data") or {}).get("id")
    T("越权实证1: B可插入A项目分镜(P0-IDOR证实)", st == 200, f"实际 {st}（200=证实IDOR；403/404=已修复）")
    if sid2:
        st, raw = req("PUT", f"/api/scenes/{sid2}", {"scriptContent": "B改A的分镜"}, token=tb)
        T("越权实证2: B可改A分镜(P0-IDOR证实)", st == 200, f"实际 {st}（200=证实IDOR）")
        req("DELETE", f"/api/scenes/{sid2}", token=tb)

    print("\n== B4.4 资产库 CRUD ==")
    st, raw = req("POST", "/api/assets", {"name": "E2E资产", "type": "character", "projectId": pid}, token=ta)
    d = parse(raw); aid = (d.get("data") or {}).get("id")
    T("建资产", st == 200 and aid, f"{st} {raw[:120]}")
    st, raw = mp_req("POST", f"/api/assets/{aid}/images", files=[("file", "a.png", PNG_1PX, "image/png")], token=ta)
    img = (parse(raw).get("data") or {}).get("images") or []
    T("资产传图", st == 200 and (parse(raw).get("data") or {}).get("url"), f"{st} {raw[:120]}")
    st, raw = req("GET", f"/api/assets?projectId={pid}&type=character", token=ta)
    T("资产列表过滤", st == 200 and len(parse(raw).get("data") or []) >= 1, f"{st}")
    st, raw = req("PUT", f"/api/assets/{aid}", {"name": "E2E资产改名"}, token=ta)
    T("改资产", st == 200, f"{st}")
    st, raw = req("DELETE", f"/api/assets/{aid}", token=ta)
    T("删资产", st == 200, f"{st}")

    print("\n== B4.5 文件上传/下载 ==")
    st, raw = mp_req("POST", "/api/files/upload", files=[("file", "t.png", PNG_1PX, "image/png")], token=ta)
    fpath = (parse(raw).get("data") or {}).get("path") or (parse(raw).get("data") or {}).get("url")
    T("文件上传", st == 200 and fpath, f"{st} {raw[:120]}")
    if fpath:
        import re
        m = re.search(r"images/([A-Za-z0-9_.-]+)", fpath)
        if m:
            st, raw = req("GET", f"/api/files/images/{m.group(1)}", base=BASE_GW)
            T("文件下载(免鉴权)", st == 200 and raw[1:4] == "PNG", f"{st} magic={raw[:4]!r}")

    print("\n== B4.6 越权实证 P1-2：直连 8084 伪造 X-User-Id ==")
    st, raw = req("GET", "/api/agent/conversations?projectId=x", headers={"X-User-Id": "fake-user-0001"}, base=BASE_MA)
    T("越权实证3: 伪造X-User-Id可访问(P1证实)", st == 200, f"实际 {st}（200=证实；401/403=已修复）")

    print("\n== B4.7 越权实证 P1-3：直连 8082 带默认 internal.secret ==")
    st, raw = req("GET", f"/api/internal/projects/{pid}", headers={"X-Internal-Token": "moon-internal-secret-2024"}, base=BASE_BE)
    T("越权实证4: 默认secret可调internal(P1证实)", st == 200, f"实际 {st}（200=证实；403/401=已修复）")
    st, raw = req("GET", f"/api/internal/projects/{pid}", base=BASE_BE)
    T("无 secret 打 /api/internal", st in (401, 403), f"{st}")
    st, raw = req("GET", f"/api/internal/projects/{pid}", base=BASE_GW)
    T("经网关打 /api/internal(应被挡)", st in (401, 403), f"{st}")

    print("\n== B4.8 AI 端点（真实调用）==")
    for attempt in (1, 2):
        st, raw = req("POST", "/api/ai/generate-script",
                      {"projectId": pid, "scriptText": "一个宇航员在火星上种土豆的短片",
                       "creationType": "custom", "customTypeDesc": "短片", "aspectRatio": "16:9"},
                      token=ta, timeout=180)
        if st == 200:
            break
        print(f"  [retry] generate-script 第{attempt}次 {st}，重试")
    d = parse(raw)
    ok = st == 200 and d.get("code") == 200
    T("generate-script(真实LLM,含1次重试)", ok and (d.get("data") or {}).get("sceneCount", 0) >= 1,
      f"{st} sceneCount={(d.get('data') or {}).get('sceneCount')}")
    st, raw = req("GET", f"/api/ai/task/{pid}__bad", token=ta)
    d2 = parse(raw)
    T("AI task 查询(无效任务→failed)", st == 200 and (d2.get("data") or {}).get("status") == "failed", f"{st} {raw[:100]}")
    st, raw = req("GET", "/api/ai/models", token=ta, timeout=30)
    T("AI models 列表(经网关)", st == 200, f"{st} {raw[:100]}")
    # 图片/视频生成：真实调用成本高，默认 SKIP（可手动开启）
    print("[SKIP] generate-image / generate-video 真实生成（成本/耗时，见报告说明）")

    print("\n== B4.9 项目状态切换与清理 ==")
    st, raw = req("PUT", f"/api/projects/{pid}", {"status": "active"}, token=ta)
    T("项目转 active", st == 200, f"{st}")
    st, raw = req("PUT", f"/api/projects/{pid}", {"status": "draft"}, token=ta)
    T("项目转 draft", st == 200, f"{st}")
    # 业务规则：至少保留一个项目 → 建第 2 个项目后删第 1 个（成功），再删第 2 个（403 规则）
    st, raw = req("POST", "/api/projects", {"name": "E2E占位项目"}, token=ta)
    pid2 = (parse(raw).get("data") or {}).get("id")
    st, raw = req("DELETE", f"/api/projects/{pid}", token=ta)
    T("删项目(非末位,级联分镜)", st == 200, f"{st} {raw[:100]}")
    st, raw = req("DELETE", f"/api/projects/{pid2}", token=ta)
    T("删末位项目(40301保留规则)", st == 403 and "至少保留" in raw, f"{st} {raw[:100]}")

    print(f"\n=== B4 完成：{'全部通过' if fails == 0 else f'{fails} 项失败'} ===")
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
