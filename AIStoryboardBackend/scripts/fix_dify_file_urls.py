#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一次性历史数据修复脚本：把 agent_messages 中残留的 Dify 工具文件签名 URL
（/files/tools/...?timestamp=...&nonce=...&sign=...）本地化。

背景：Dify 工作流 LLM 输出引用的图片/视频 URL 是 Dify 内部文件服务的带时效签名 URL，
过期后（数分钟~数小时）访问返回 403，而消息已持久化 —— 前端刷新重放必然裂图。

处理策略（逐条消息、逐 URL）：
1. 签名仍有效（可下载）→ 下载到 uploads/images/，替换为 /api/files/images/xxx.png 永久 URL
2. 下载失败（已过期/文件已删）→ 图片标记降级为文本「（图片已过期）」

用法：
    python fix_dify_file_urls.py            # dry-run：只打印计划
    python fix_dify_file_urls.py --apply    # 实际执行

依赖：psql 命令行（PATH 中可用）+ 标准库。数据库参数从 AIStoryboardBackend/.env 读取。
"""
import os
import re
import subprocess
import sys
import uuid
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # AIStoryboardBackend/
UPLOADS_DIR = ROOT / 'uploads' / 'images'

# ── 读取 .env 数据库配置 ──────────────────────────────────────────────
env = {}
env_file = ROOT / '.env'
if env_file.exists():
    for line in env_file.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        k, _, v = line.partition('=')
        env[k.strip()] = v.strip()

DB_HOST = env.get('DB_HOST', 'localhost')
DB_PORT = env.get('DB_PORT', '5432')
DB_NAME = env.get('DB_NAME', 'newworkflow')
DB_USER = env.get('DB_USERNAME', 'postgres')
DB_PASS = env.get('DB_PASSWORD', '')


def psql(sql: str) -> str:
    """执行 SQL（psql 子进程），返回 stdout（去尾空白）。"""
    proc_env = dict(os.environ, PGPASSWORD=DB_PASS)
    cmd = ['psql', '-h', DB_HOST, '-p', DB_PORT, '-U', DB_USER, '-d', DB_NAME,
           '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-c', sql]
    r = subprocess.run(cmd, capture_output=True, text=True, env=proc_env, encoding='utf-8')
    if r.returncode != 0:
        raise RuntimeError(f'psql 执行失败: {r.stderr.strip()}')
    return r.stdout.strip()


# Dify 工具文件 URL：http(s)://host/files/tools/xxx.ext?签名，或裸 /files/tools/xxx.ext?签名
URL_RE = re.compile(r'(?:https?://[A-Za-z0-9\-._~:]+)?/files/tools/[A-Za-z0-9\-._~:/?&=+%]+')


def download(url: str):
    """下载 URL 到 uploads/images，返回 /api/files/images/xxx.ext 相对路径；失败抛异常。"""
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = resp.read()
    path_part = url.split('?')[0]
    name = path_part.rsplit('/', 1)[-1]
    ext = name.rsplit('.', 1)[-1].lower() if '.' in name else 'png'
    if ext not in ('png', 'jpg', 'jpeg', 'webp', 'gif'):
        ext = 'png'
    filename = f'{uuid.uuid4()}.{ext}'
    (UPLOADS_DIR / filename).write_bytes(data)
    return f'/api/files/images/{filename}'


def main() -> None:
    apply = '--apply' in sys.argv
    UPLOADS_DIR.mkdir(parents=True, exist_ok=True)

    # content 换行用 chr(10) 转义为 \n 文本，保证 psql -A 输出单行可解析
    sql = ("SELECT id, replace(content, chr(10), '\\\\n') "
           "FROM agent_messages WHERE content LIKE '%/files/tools/%'")
    rows = psql(sql)
    if not rows:
        print('没有需要修复的消息（所有消息均不含 /files/tools/ URL）')
        return

    msgs = []
    for line in rows.splitlines():
        mid, _, content = line.partition('|')
        msgs.append((mid, content.replace('\\n', '\n')))

    print(f'发现 {len(msgs)} 条含 Dify 工具文件 URL 的消息（{"执行模式" if apply else "dry-run 模式"}）')
    total_downloaded = 0
    total_degraded = 0

    for mid, content in msgs:
        urls = sorted(set(URL_RE.findall(content)))
        new_content = content
        for url in urls:
            try:
                local = download(url)
                new_content = new_content.replace(url, local)
                total_downloaded += 1
                print(f'  [下载成功] msg={mid[:8]}… {url[:60]}… -> {local}')
            except Exception as e:
                # 降级：URL → 文本（markdown 图片标记整体变为纯文本）
                new_content = new_content.replace(url, '（图片已过期）')
                total_degraded += 1
                print(f'  [下载失败] msg={mid[:8]}… {url[:60]}… 降级为文本（{type(e).__name__}: {e}）')

        if new_content != content:
            if apply:
                escaped = new_content.replace("'", "''")
                psql(f"UPDATE agent_messages SET content = '{escaped}' WHERE id = '{mid}'")
                print(f'  [已更新] msg={mid[:8]}…')
            else:
                print(f'  [待更新] msg={mid[:8]}…')

    print(f'\n汇总：本地化 {total_downloaded} 个 URL，降级 {total_degraded} 个 URL')
    if not apply:
        print('（dry-run 未写库，确认无误后加 --apply 执行）')


if __name__ == '__main__':
    main()
