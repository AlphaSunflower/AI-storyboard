#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B2 认证引导：注册双用户 → 登录 → refresh → profile → 落 .creds.json。
userA=正常用户；userB=越权测试用（第二用户）。"""
import sys
import time

sys.path.insert(0, "scripts/e2e")
from common import req, parse, rand_email, save_creds, check, BASE_GW


def main():
    print("== B2 认证引导 ==")
    email_a, email_b = rand_email(), rand_email()
    pwd = "e2ePass123!"
    creds = {}

    # 注册
    for tag, email in (("A", email_a), ("B", email_b)):
        st, raw = req("POST", "/api/auth/register",
                      {"email": email, "password": pwd, "displayName": f"E2E{tag}用户"})
        d = parse(raw)
        ok = st == 200 and d.get("code") in (0, 200)
        check(f"注册用户{tag} {email}", ok, f"{st} {raw[:150]}")
        if not ok:
            return 1
        creds[f"user{tag}"] = {"email": email, "password": pwd}

    # 登录
    for tag in ("A", "B"):
        st, raw = req("POST", "/api/auth/login",
                      {"email": creds[f"user{tag}"]["email"], "password": pwd})
        d = parse(raw)
        data = d.get("data") or {}
        ok = st == 200 and data.get("accessToken") and data.get("refreshToken")
        check(f"登录用户{tag}", ok, f"{st} {raw[:150]}")
        if not ok:
            return 1
        creds[f"user{tag}"]["accessToken"] = data["accessToken"]
        creds[f"user{tag}"]["refreshToken"] = data["refreshToken"]

    # refresh 换新 token
    st, raw = req("POST", "/api/auth/refresh",
                  {"refreshToken": creds["userA"]["refreshToken"]})
    d = parse(raw)
    new_token = (d.get("data") or {}).get("accessToken")
    ok = st == 200 and bool(new_token) and new_token != creds["userA"]["accessToken"]
    check("refresh 换新 accessToken", ok, f"{st} {raw[:150]}")
    creds["userA"]["accessToken"] = new_token

    # profile 验证 token 有效
    st, raw = req("GET", "/api/user/profile", token=creds["userA"]["accessToken"])
    d = parse(raw)
    check("GET /api/user/profile", st == 200 and d.get("code") in (0, 200), f"{st} {raw[:150]}")

    # 无 token 401
    st, raw = req("GET", "/api/user/profile")
    check("无 token → 401", st == 401, f"{st}")

    creds["password"] = pwd
    creds["base_gw"] = BASE_GW
    save_creds(creds)
    print(f"凭据已存 {__import__('os').path.abspath('scripts/e2e/.creds.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
