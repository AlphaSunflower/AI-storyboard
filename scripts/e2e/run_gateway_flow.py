#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B6 网关 E2E：AILLMGateway /v1 + /admin + ApiGateway 穿透与 internal 拦截。
前置：E2E 专用 admin（e2eadmin/E2Eadmin123!）与网关 key（/tmp/e2e_key_plain.txt）已造表。"""
import sys

sys.path.insert(0, "scripts/e2e")
from common import req, parse, check, load_creds, BASE_GW, BASE_LLM, BASE_BE

API_KEY = open("/tmp/e2e_key_plain.txt").read().strip()


def main():
    c = load_creds()
    ta = c["userA"]["accessToken"]
    fails = 0

    def T(label, cond, detail=""):
        nonlocal fails
        if not check(label, cond, detail):
            fails += 1

    print("\n== B6.1 /v1/** API Key 鉴权 ==")
    st, raw = req("GET", "/v1/models", base=BASE_LLM)
    T("无 key → 401", st == 401, f"{st}")
    st, raw = req("GET", "/v1/models", headers={"Authorization": "Bearer wrong-key"}, base=BASE_LLM)
    T("错 key → 401", st == 401, f"{st}")
    st, raw = req("GET", "/v1/models", headers={"Authorization": "Bearer " + API_KEY}, base=BASE_LLM)
    d = parse(raw)
    T("正确 key → 200 模型列表", st == 200 and ("data" in d or isinstance(d, list)), f"{st} {raw[:120]}")
    st, raw = req("POST", "/v1/chat/completions",
                  {"model": "deepseek-v4-flash", "messages": [{"role": "user", "content": "用一句话介绍你自己"}],
                   "max_tokens": 50},
                  headers={"Authorization": "Bearer " + API_KEY}, base=BASE_LLM, timeout=90)
    d = parse(raw)
    content = ""
    try:
        content = d["choices"][0]["message"]["content"]
    except Exception:
        pass
    T("chat/completions(真实上游)", st == 200 and bool(content), f"{st} {raw[:150]}")

    print("\n== B6.2 /v1/images 非法 size 降级/拒绝 ==")
    st, raw = req("POST", "/v1/images/generations",
                  {"model": "gpt-image-2", "prompt": "a red circle", "size": "2K", "n": 1},
                  headers={"Authorization": "Bearer " + API_KEY}, base=BASE_LLM, timeout=120)
    # 2K 非法：400（网关白名单拒绝）或 200（上游接受）；不允许 5xx
    T("非法 size 2K → 4xx 或 200", st in (200, 400, 422), f"{st} {raw[:150]}")

    print("\n== B6.3 /admin/** 鉴权 ==")
    st, raw = req("GET", "/admin/channels", base=BASE_LLM)
    T("admin 无 token → 401", st == 401, f"{st}")
    st, raw = req("POST", "/admin/login", {"username": "e2eadmin", "password": "E2Eadmin123!"}, base=BASE_LLM)
    d = parse(raw)
    adm = (d.get("data") or {}).get("accessToken") or (d.get("data") or {}).get("token")
    T("admin login", st == 200 and adm, f"{st} {raw[:150]}")
    if not adm:
        print("[FAIL] 无 admin token，跳过 admin 全查")
        fails += 1
        return 1
    for path in ("/admin/channels", "/admin/routes", "/admin/api-keys",
                 "/admin/users", "/admin/call-logs?page=1&size=5", "/admin/stats/overview",
                 "/admin/config", "/admin/model-params/gpt-image-2"):
        st, raw = req("GET", path, token=adm, base=BASE_LLM)
        body = raw.lower()
        leak = any(k in body for k in ("passwordhash", '"password"', "plainkey", "aes_key"))
        T(f"admin GET {path}", st == 200 and not leak, f"{st} {'密钥字段泄漏!' if leak else raw[:100]}")
    st, raw = req("GET", "/admin-ui", base=BASE_LLM)
    T("admin-ui 静态页", st == 200, f"{st}")

    print("\n== B6.4 经网关穿透与拦截 ==")
    st, raw = req("GET", "/api/user/profile", token=ta, base=BASE_GW)
    T("经网关 /api/user/profile", st == 200, f"{st}")
    st, raw = req("POST", "/api/auth/login", {"email": c["userA"]["email"], "password": c["password"]}, base=BASE_GW)
    T("经网关 /api/auth/login 免鉴权", st == 200, f"{st}")
    st, raw = req("GET", "/api/internal/projects/x", base=BASE_GW)
    T("经网关 /api/internal → 拦截", st in (401, 403), f"{st}")
    st, raw = req("GET", "/api/agent/conversations?projectId=x", base=BASE_GW)
    T("经网关 /api/agent → MoonAgent 401", st == 401, f"{st}")
    st, raw = req("GET", "/api/agent/prompt/optimize", base=BASE_GW)
    T("经网关 /api/agent 白名单外 401", st == 401, f"{st}")

    print(f"\n=== B6 完成：{'全部通过' if fails == 0 else f'{fails} 项失败'} ===")
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
