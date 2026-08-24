# -*- coding: utf-8 -*-
"""生成扩充配图：图8 交互时序图 / 图10 部署架构图"""
import os, subprocess

OUT = r"E:\Desktop\plan-img"
os.makedirs(OUT, exist_ok=True)
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def svg_head(w, h):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><defs><style>text{{font-family:"Microsoft YaHei",sans-serif}} .t{{font-size:19px;font-weight:bold;fill:#141413}} .s{{font-size:14px;fill:#141413}} .n{{font-size:12px;fill:#6b6b66}} .cap{{font-size:16px;font-weight:bold;fill:#cc785c}} .msg{{font-size:12.5px;fill:#141413}}</style></defs><rect width="{w}" height="{h}" fill="#ffffff"/>'

def box(x, y, w, h, fill, stroke="#141413", rx=6):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>'

def txt(x, y, s, cls="s", anchor="middle"):
    return f'<text x="{x}" y="{y}" class="{cls}" text-anchor="{anchor}">{esc(s)}</text>'

def line(x1, y1, x2, y2, color="#8a8a85", w=1.4, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{w}"{d}/>'

# ============ 图8 对话式创作交互时序图 ============
def fig_seq():
    w, h = 1280, 780
    actors = ["用户（前端）", "API Gateway", "MoonAgent 微服务", "主后端", "LLM 网关", "上游模型"]
    xs = [110, 330, 550, 770, 990, 1180]
    svg = [svg_head(w, h), txt(640, 30, "图 8  对话式分镜创作交互时序图", "cap")]
    for x, a in zip(xs, actors):
        svg.append(box(x - 80, 55, 160, 44, "#fdf6ec"))
        svg.append(txt(x, 82, a, "n"))
    svg.append(line(xs[0], 99, xs[0], 720, "#b0836a", 1.2))
    svg.append(line(xs[1], 99, xs[1], 720, "#b0836a", 1.2))
    svg.append(line(xs[2], 99, xs[2], 720, "#b0836a", 1.2))
    svg.append(line(xs[3], 99, xs[3], 720, "#b0836a", 1.2))
    svg.append(line(xs[4], 99, xs[4], 720, "#b0836a", 1.2))
    svg.append(line(xs[5], 99, xs[5], 720, "#b0836a", 1.2))

    msgs = [
        (0, 1, "1 输入需求（自然语言）", 120),
        (1, 2, "2 POST /messages/stream（SSE）", 158),
        (2, 4, "3 意图识别（规则前置 + LLM 置信度）", 196),
        (2, 0, "4 SSE message 增量（打字机效果）", 234),
        (2, 0, "5 human_input（HITL 确认卡片 + 参数选择器）", 272),
        (0, 1, "6 确认方案（覆盖/追加/重新生成）", 310),
        (1, 2, "7 POST /form/submit（一次性 form_token）", 348),
        (2, 3, "8 内部 API 写分镜/资产（X-Internal-Token）", 386),
        (2, 4, "9 生图 / 图生视频（OpenAI 风格请求）", 424),
        (4, 5, "10 转发上游 + 协议转换 + 密钥管理", 462),
        (2, 0, "11 task_accepted（视频异步受理）→ 前端 5s 轮询", 500),
        (3, 2, "12 任务完成回调：素材落库归档", 538),
        (2, 0, "13 message_end（含 sceneCount，触发前端刷新）", 576),
    ]
    for a, b, label, y in msgs:
        y1, y2 = y, y + 24
        svg.append(line(xs[a], y, xs[b], y, "#141413", 1.6))
        # 箭头
        svg.append(f'<polygon points="{xs[b]-7},{y} {xs[b]+3},{y-5} {xs[b]+3},{y+5}" fill="#141413"/>')
        svg.append(txt((xs[a] + xs[b]) / 2, y - 6, label, "msg"))
    svg.append(txt(640, 610, "关键设计：HITL 确认卡片经 checkpoint（form_token 一次性 + 30 分钟过期）持久化；", "n"))
    svg.append(txt(640, 632, "视频生成采用异步任务（task_accepted 立即返回 + 5 秒轮询），长耗时操作不阻塞交互链路。", "n"))
    svg.append("</svg>")
    return "".join(svg)

# ============ 图10 私有化部署架构图 ============
def fig_deploy():
    w, h = 1280, 760
    svg = [svg_head(w, h), txt(640, 30, "图 10  企业私有化部署架构图（全容器化 Docker Compose）", "cap")]
    # 用户侧
    svg.append(box(60, 80, 220, 120, "#fdf6ec"))
    svg.append(txt(170, 115, "企业内网用户", "t"))
    svg.append(txt(170, 145, "浏览器 / 客户端", "s"))
    svg.append(txt(170, 170, "（HTTPS 访问）", "n"))
    # 网关
    svg.append(box(420, 80, 220, 120, "#f7e8d8"))
    svg.append(txt(530, 115, "API Gateway", "t"))
    svg.append(txt(530, 145, "端口 8080", "s"))
    svg.append(txt(530, 170, "JWT 验签 / 路由 / CORS", "n"))
    # 服务区
    services = [
        ("主后端", "8082", "项目/分镜/资产/文件"),
        ("MoonAgent", "8084", "智能体编排/HITL"),
        ("LLM 网关", "8083", "模型路由/密钥管理"),
        ("Nacos", "8848", "服务注册与发现"),
        ("PostgreSQL", "5432", "业务库 + 网关库"),
    ]
    y = 300
    for name, port, desc in services:
        svg.append(box(420, y, 440, 58, "#f3dfc9"))
        svg.append(txt(500, y + 24, name, "t", "start"))
        svg.append(txt(500, y + 44, desc, "n", "start"))
        svg.append(txt(820, y + 24, port, "s"))
        svg.append(txt(640, y + 74, "", "n"))
        y += 78
    # 容器边界
    svg.append(box(390, 270, 500, 440, "none", "#8a5a44", 10))
    svg.append(txt(640, 292, "Docker Compose 编排（离线镜像包，内网部署）", "t"))
    # 外部模型
    svg.append(box(980, 300, 230, 150, "#fdf6ec"))
    svg.append(txt(1095, 335, "企业自有模型渠道", "t"))
    svg.append(txt(1095, 365, "云端 API（方式一）", "n"))
    svg.append(txt(1095, 385, "内网模型服务（方式二）", "n"))
    svg.append(txt(1095, 405, "混合模式（方式三）", "n"))
    # 连线
    svg.append(line(280, 140, 420, 140, "#141413", 2))
    svg.append(line(530, 200, 530, 270, "#141413", 2))
    svg.append(line(860, 335, 980, 335, "#141413", 1.6, dash="6,4"))
    svg.append(txt(640, 750, "部署形态：全容器化 + 配置化（渠道/模型/参数后台可视化配置，无需改代码），实施周期 1-2 周", "n"))
    svg.append("</svg>")
    return "".join(svg)

figs = {"fig_seq": (fig_seq, "fig_seq.png", 1280, 780),
        "fig_deploy": (fig_deploy, "fig_deploy.png", 1280, 760)}
for name, (fn, png, w, h) in figs.items():
    svg_path = os.path.join(OUT, name + ".svg")
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write(fn())
    png_path = os.path.join(OUT, png)
    r = subprocess.run([CHROME, "--headless", "--disable-gpu",
                        f"--screenshot={png_path}", f"--window-size={w},{h}",
                        "file:///" + svg_path.replace("\\", "/")],
                       capture_output=True, text=True, timeout=120)
    sz = os.path.getsize(png_path) if os.path.exists(png_path) else 0
    print(name, "png_size=", sz, "ok" if sz > 30000 else "CHECK")
