# -*- coding: utf-8 -*-
"""生成图 9 移动端界面示意图（/chat 对话页 + 底部导航）"""
import os, subprocess

OUT = r"E:\Desktop\plan-img"
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

svg = '''<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="760" viewBox="0 0 1280 760">
<defs><style>text{font-family:"Microsoft YaHei",sans-serif} .t{font-size:19px;font-weight:bold;fill:#141413} .s{font-size:14px;fill:#141413} .n{font-size:12.5px;fill:#6b6b66} .cap{font-size:16px;font-weight:bold;fill:#cc785c} .msg{font-size:12px;fill:#141413}</style></defs>
<rect width="1280" height="760" fill="#ffffff"/>
<text x="640" y="30" class="cap" text-anchor="middle">图 9  移动端界面示意图（/chat 对话页，≤768px 断点）</text>
<!-- 手机框 -->
<rect x="480" y="60" width="320" height="640" rx="28" fill="#faf9f5" stroke="#141413" stroke-width="2"/>
<!-- 状态栏 + 标题 -->
<rect x="480" y="60" width="320" height="36" rx="28" fill="#141413"/>
<rect x="480" y="70" width="320" height="26" fill="#141413"/>
<text x="640" y="122" class="t" text-anchor="middle">☾ 链枢镜 Agent</text>
<text x="640" y="144" class="n" text-anchor="middle">咖啡广告分镜 · 会话</text>
<!-- 对话流 -->
<rect x="560" y="160" width="224" height="52" rx="12" fill="#cc785c"/>
<text x="672" y="184" class="msg" text-anchor="middle" fill="#ffffff">帮我设计一个 30 秒</text>
<text x="672" y="202" class="msg" text-anchor="middle" fill="#ffffff">咖啡广告分镜</text>
<rect x="500" y="224" width="236" height="120" rx="12" fill="#efe9de"/>
<text x="512" y="246" class="msg" text-anchor="start">已识别：分镜创作意图</text>
<text x="512" y="266" class="msg" text-anchor="start">1. 咖啡拉花特写（2s）</text>
<text x="512" y="286" class="msg" text-anchor="start">2. 咖啡馆全景（3s）</text>
<text x="512" y="306" class="msg" text-anchor="start">3. 产品海报（3s）…</text>
<text x="512" y="330" class="n" text-anchor="start">▌ 打字机输出中…</text>
<rect x="560" y="356" width="224" height="64" rx="12" fill="#ffffff" stroke="#cc785c" stroke-width="1.2"/>
<text x="672" y="380" class="msg" text-anchor="middle">HITL 确认卡片</text>
<text x="672" y="400" class="n" text-anchor="middle">[覆盖导入] [追加] [不满意]</text>
<!-- 底部输入卡 -->
<rect x="498" y="600" width="284" height="52" rx="26" fill="#ffffff" stroke="#141413" stroke-width="1.2"/>
<text x="620" y="632" class="n" text-anchor="middle">继续完善…（Safe Area 避让）</text>
<circle cx="756" cy="626" r="16" fill="#cc785c"/>
<text x="756" y="632" class="msg" text-anchor="middle" fill="#ffffff">➤</text>
<!-- 底部导航 -->
<rect x="480" y="662" width="320" height="38" rx="0" fill="#ffffff"/>
<line x1="480" y1="662" x2="800" y2="662" stroke="#e5ddcd" stroke-width="1"/>
<text x="520" y="688" class="n" text-anchor="middle">聊天</text>
<text x="592" y="688" class="n" text-anchor="middle">创作</text>
<text x="664" y="688" class="n" text-anchor="middle">项目</text>
<text x="736" y="688" class="n" text-anchor="middle">我的</text>
<!-- 标注 -->
<line x1="462" y1="612" x2="380" y2="612" stroke="#8a5a44" stroke-width="1.4"/>
<text x="372" y="606" class="n" text-anchor="end">底部安全区避让</text>
<line x1="818" y1="180" x2="900" y2="150" stroke="#8a5a44" stroke-width="1.4"/>
<text x="908" y="154" class="n" text-anchor="start">触控热区 ≥44px</text>
<line x1="818" y1="360" x2="900" y2="330" stroke="#8a5a44" stroke-width="1.4"/>
<text x="908" y="334" class="n" text-anchor="start">HITL 卡片完整保留</text>
<text x="640" y="742" class="n" text-anchor="middle">移动端能力不缩水：模型/参数选择、HITL 确认、SSE 打字机均完整可用；底部导航跨页复用（聊天/创作/项目/我的）</text>
</svg>'''

svg_path = os.path.join(OUT, "fig_mobile.svg")
with open(svg_path, "w", encoding="utf-8") as f:
    f.write(svg)
png_path = os.path.join(OUT, "fig_mobile.png")
subprocess.run([CHROME, "--headless", "--disable-gpu",
                f"--screenshot={png_path}", "--window-size=1280,760",
                "file:///" + svg_path.replace("\\", "/")],
               capture_output=True, text=True, timeout=120)
sz = os.path.getsize(png_path)
print("fig_mobile png_size=", sz, "ok" if sz > 30000 else "CHECK")
