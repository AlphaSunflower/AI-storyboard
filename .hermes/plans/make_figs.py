# -*- coding: utf-8 -*-
"""生成计划书新增配图 SVG -> Chrome headless PNG（图5 功能模块 / 图6 ER / 图7 信息架构 / 图9 收入预测）"""
import os, subprocess

OUT = r"E:\Desktop\plan-img"
os.makedirs(OUT, exist_ok=True)
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

def esc(s):  # 文本转义
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def svg_head(w, h):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><defs><style>text{{font-family:"Microsoft YaHei",sans-serif}} .t{{font-size:19px;font-weight:bold;fill:#141413}} .s{{font-size:14px;fill:#141413}} .n{{font-size:12px;fill:#6b6b66}} .cap{{font-size:16px;font-weight:bold;fill:#cc785c}}</style></defs><rect width="{w}" height="{h}" fill="#ffffff"/>'

def box(x, y, w, h, fill, stroke="#141413", rx=6):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>'

def txt(x, y, s, cls="s", anchor="middle"):
    return f'<text x="{x}" y="{y}" class="{cls}" text-anchor="{anchor}">{esc(s)}</text>'

def line(x1, y1, x2, y2, color="#8a8a85", w=1.4, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{w}"{d}/>'

# ============ 图5 系统功能模块图（四层 + 数据层） ============
def fig5():
    w, h = 1280, 780
    layers = [
        ("交互层", 40, "前端应用（React 19 + TypeScript 6 + Vite 8）", ["分镜编辑器（三栏布局）", "Moon 智能体抽屉", "资产库面板", "移动端适配"], "#fdf6ec"),
        ("网关层", 180, "API Gateway（Spring Cloud Gateway + Nacos）", ["JWT 统一验签", "路由分发", "统一 CORS", "内部接口屏蔽"], "#f7e8d8"),
        ("应用服务层", 320, "主后端 + MoonAgent 智能体微服务", ["项目/分镜/资产/文件服务", "认证与用户管理", "意图识别与策略编排", "HITL 确认 / 会话管理", "文生图·图改图·图生视频"], "#f3dfc9"),
        ("AI 能力层", 520, "LLM 网关（AILLMGateway）", ["文本模型", "图像模型", "视频模型", "视觉理解模型"], "#efd7bd"),
    ]
    svg = [svg_head(w, h), txt(640, 30, "图 5  系统功能模块图", "cap")]
    ybox = 55
    for name, y, label, mods, fill in layers:
        svg.append(box(60, y, 1160, 96, fill))
        svg.append(txt(100, y + 30, name, "t", "start"))
        svg.append(txt(100, y + 56, label, "s", "start"))
        bx = 520
        for m in mods:
            svg.append(box(bx, y + 22, 168, 52, "#ffffff"))
            svg.append(txt(bx + 84, y + 52, m, "n"))
            bx += 176
        ybox = y
    # 数据层
    dy = 650
    svg.append(box(60, dy, 1160, 76, "#f9f4ea"))
    svg.append(txt(100, dy + 28, "数据层", "t", "start"))
    svg.append(txt(100, dy + 54, "PostgreSQL（业务库 8 表 + 网关库）  |  文件存储（uploads 共享目录）  |  服务注册 Nacos", "s", "start"))
    svg.append(txt(640, 758, "数据流向：交互层 → 网关层 → 应用服务层 → AI 能力层 → 数据层", "n"))
    svg.append("</svg>")
    return "".join(svg)

# ============ 图6 数据库 ER 图 ============
def fig6():
    w, h = 1280, 780
    tables = [
        ("users", ["id  PK", "username", "password_hash", "role"], 60, 60),
        ("projects", ["id  PK", "user_id  FK→users", "title", "status"], 60, 220),
        ("scenes", ["id  PK", "project_id  FK→projects", "content JSON", "image_url / video_url"], 60, 380),
        ("scene_assets", ["scene_id  FK→scenes", "asset_id  FK→agent_assets"], 60, 560),
        ("conversations", ["id  PK", "user_id  FK→users", "project_id  FK→projects", "title / status"], 700, 60),
        ("agent_messages", ["id  PK", "conversation_id  FK", "role", "content"], 700, 220),
        ("agent_assets", ["id  PK", "conversation_id  FK?", "type / url", "task_id / status"], 700, 380),
        ("agent_checkpoints", ["id  PK", "conversation_id  FK", "form_token 一次性", "plan JSON / status"], 700, 560),
    ]
    svg = [svg_head(w, h), txt(640, 30, "图 6  核心数据库 ER 图（主后端业务库，含 LLM 网关独立库 model_route/model_params）", "cap")]
    for name, fields, x, y in tables:
        bh = 30 + 22 * len(fields) + 8
        svg.append(box(x, y, 380, bh, "#fdf6ec"))
        svg.append(txt(x + 190, y + 22, name, "t"))
        fy = y + 42
        for f in fields:
            svg.append(txt(x + 16, fy, f, "n", "start"))
            fy += 22
    # 连线
    conns = [(60+380, 84, 700, 84), (60+380, 244, 700, 244), (60+380, 404, 700, 404), (60+380, 584, 700, 584)]
    for x1, y1, x2, y2 in conns:
        svg.append(line(x1, y1, x2, y2, "#b0836a", 2))
    # 归属线（users->conversations 等，简化竖直引导线）
    svg.append(txt(640, 760, "说明：conversation_id 可空 = 未归属素材；scene_assets 为分镜-资产多对多关联表", "n"))
    svg.append("</svg>")
    return "".join(svg)

# ============ 图7 编辑器信息架构与布局 ============
def fig7():
    w, h = 1280, 780
    svg = [svg_head(w, h), txt(640, 30, "图 7  编辑器信息架构与布局（桌面端三栏 + 智能体抽屉）", "cap")]
    # 窗口外框
    svg.append(box(60, 55, 1160, 600, "#ffffff", "#141413", 10))
    svg.append(box(60, 55, 1160, 36, "#141413", rx=10))
    svg.append(txt(120, 78, "AI Storyboard 编辑器窗口", "cap", "start"))
    # 左栏 会话栏
    svg.append(box(76, 105, 180, 534, "#fdf6ec"))
    svg.append(txt(166, 130, "会话栏（180px）", "t"))
    for i, t in enumerate(["会话列表", "Moon 智能体入口", "项目切换"]):
        svg.append(box(88, 150 + i * 46, 156, 34, "#ffffff"))
        svg.append(txt(166, 172 + i * 46, t, "n"))
    # 中栏 分镜列表
    svg.append(box(268, 105, 380, 534, "#faf9f5"))
    svg.append(txt(458, 130, "分镜列表（380-600px 可拖拽）", "t"))
    for i, t in enumerate(["镜头 1  咖啡拉花特写", "镜头 2  咖啡馆全景", "镜头 3  产品海报"]):
        svg.append(box(282, 150 + i * 76, 352, 62, "#ffffff"))
        svg.append(txt(458, 174 + i * 76, t, "s"))
        svg.append(txt(458, 194 + i * 76, "已完成 · 图片 ✓ · 视频 ✓", "n"))
    svg.append(txt(458, 402, "＋ 添加镜头", "s"))
    # 拖拽把手
    svg.append(box(650, 105, 8, 534, "#e5ddcd"))
    # 右栏 预览面板
    svg.append(box(668, 105, 536, 534, "#fdf6ec"))
    svg.append(txt(936, 130, "预览面板（弹性宽度）", "t"))
    svg.append(box(686, 148, 500, 300, "#efe9de"))
    svg.append(txt(936, 300, "场景预览 / 灯箱", "s"))
    svg.append(box(686, 462, 500, 60, "#ffffff"))
    svg.append(txt(936, 498, "模型参数选择器（LLM 推荐 + 理由）", "s"))
    # 悬浮球
    svg.append(box(1150, 560, 44, 44, "#cc785c", rx=22))
    svg.append(txt(1172, 587, "☾", "t"))
    # 抽屉
    svg.append(box(788, 105, 420, 534, "#ffffff", "#cc785c", 8))
    svg.append(txt(998, 130, "Moon 智能体抽屉（62vw）", "t"))
    svg.append(box(806, 148, 384, 300, "#faf9f5"))
    svg.append(txt(998, 250, "对话式创作（SSE 打字机）", "s"))
    svg.append(txt(998, 290, "HITL 确认卡片", "s"))
    svg.append(txt(998, 330, "产出素材", "s"))
    svg.append(box(806, 462, 384, 56, "#efe9de"))
    svg.append(txt(998, 496, "输入区（可拖拽伸缩 90px-40vh）", "n"))
    svg.append(txt(640, 690, "交互层级：悬浮球 → 抽屉；预览点击 → 灯箱；确认卡 → 参数选择器 → 提交", "n"))
    svg.append("</svg>")
    return "".join(svg)

# ============ 图9 三年收入预测图（三情景柱状） ============
def fig9():
    w, h = 1280, 620
    svg = [svg_head(w, h), txt(640, 30, "图 9  三年收入预测（三情景，单位：万元）", "cap")]
    scenarios = {"保守": (5, 25, 110), "中性": (7.3, 52, 233), "乐观": (10, 90, 480)}
    colors = {"保守": "#c8beb2", "中性": "#cc785c", "乐观": "#8a5a44"}
    # 坐标区
    ox, oy, gw, gh = 140, 70, 1000, 400
    svg.append(line(ox, oy + gh, ox + gw, oy + gh, "#141413", 2))
    svg.append(line(ox, oy, ox, oy + gh, "#141413", 2))
    for tick in range(0, 501, 100):
        ty = oy + gh - tick / 500 * gh
        svg.append(line(ox, ty, ox + gw, ty, "#e5ddcd", 1))
        svg.append(txt(ox - 10, ty + 5, str(tick), "n", "end"))
    # 每情景 3 年柱
    years = ["第一年", "第二年", "第三年"]
    bw = 70
    for si, (name, vals) in enumerate(scenarios.items()):
        cx = ox + 130 + si * 280
        for yi, v in enumerate(vals):
            x = cx + (yi - 1) * 90
            bh = v / 500 * gh
            svg.append(box(x, oy + gh - bh, bw, bh, colors[name]))
            svg.append(txt(x + bw / 2, oy + gh - bh - 8, str(v), "n"))
        svg.append(txt(cx + 15, oy + gh + 28, name + "情景", "s"))
    for yi, yl in enumerate(years):
        svg.append(txt(ox + 130 + yi * 90 + 35, oy + gh + 55, yl, "n"))
    # 图例
    lx = 940
    for name, c in colors.items():
        svg.append(box(lx, 40, 26, 16, c))
        svg.append(txt(lx + 34, 53, name, "n", "start"))
        lx += 110
    svg.append(txt(640, 610, "数据口径：注册用户/付费转化率/ARPU 三情景假设见第六章表 9、表 10（测算假设值）", "n"))
    svg.append("</svg>")
    return "".join(svg)

figs = {"fig5": (fig5, "fig5.png", 1280, 780),
        "fig6": (fig6, "fig6.png", 1280, 780),
        "fig7": (fig7, "fig7.png", 1280, 780),
        "fig9": (fig9, "fig9.png", 1280, 620)}

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
    print(name, "png_size=", sz, "ok" if sz > 50000 else "CHECK")
