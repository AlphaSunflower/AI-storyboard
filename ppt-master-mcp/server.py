#!/usr/bin/env python3
"""
PPT Master MCP Server

将 ppt-master 的核心脚本暴露为 MCP 工具，供 LLM 调用来驱动 PPT 生成。

架构：调用方 LLM 负责创意（写 SVG），MCP server 负责脚手架（项目管理、质量检查、导出）。

Usage:
    python server.py                    # stdio 模式（默认）
    python server.py --port 8084        # SSE 模式
"""

from __future__ import annotations

import asyncio
import glob
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

from mcp.server import MCPServer

# ── 路径常量 ──────────────────────────────────────────────
PPT_MASTER_DIR = Path(os.environ.get(
    "PPT_MASTER_SKILL_DIR",
    Path.home() / "AppData/Local/hermes/skills/productivity/ppt-master"
))
SCRIPTS_DIR = PPT_MASTER_DIR / "scripts"
# project_manager.py 默认在 scripts/projects/ 下创建项目
DEFAULT_PROJECTS_DIR = SCRIPTS_DIR / "projects"

# ── Server 实例 ──────────────────────────────────────────
server = MCPServer(
    name="ppt-master",
    instructions=(
        "PPT Master MCP 工具集。用于创建、编辑和导出演示文稿。\n"
        "典型工作流：\n"
        "1. list_formats 查看可用画布格式\n"
        "2. init_project 创建项目\n"
        "3. create_scaffold 生成 spec 脚手架\n"
        "4. 调用方 LLM 编写 SVG 页面 → write_svg_page 保存\n"
        "5. quality_check 质量检查\n"
        "6. export_pptx 导出最终文件\n"
    ),
)


# ── 辅助函数 ─────────────────────────────────────────────
def _run_script(script_name: str, args: list[str], cwd: str | None = None) -> dict[str, Any]:
    """运行 ppt-master 脚本，返回 {exit_code, stdout, stderr}。"""
    cmd = [sys.executable, str(SCRIPTS_DIR / script_name)] + args
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=120,
            cwd=cwd or str(SCRIPTS_DIR),
            env={**os.environ, "PYTHONPATH": str(SCRIPTS_DIR)},
        )
        return {
            "exit_code": result.returncode,
            "stdout": result.stdout[-3000:],
            "stderr": result.stderr[-1000:],
        }
    except subprocess.TimeoutExpired:
        return {"exit_code": -1, "stdout": "", "stderr": "脚本执行超时（120s）"}
    except Exception as e:
        return {"exit_code": -1, "stdout": "", "stderr": str(e)}


def _find_project(name: str) -> Path | None:
    """在 projects 目录下查找匹配的项目目录。

    project_manager.py init 会在目录名后追加 _<format>_<timestamp>，
    所以用前缀匹配：name* 格式。
    """
    if not DEFAULT_PROJECTS_DIR.exists():
        return None
    # 精确匹配
    exact = DEFAULT_PROJECTS_DIR / name
    if exact.exists():
        return exact
    # 前缀匹配（name_fmt_timestamp 模式）
    candidates = sorted(DEFAULT_PROJECTS_DIR.glob(f"{name}_*"), key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates[0] if candidates else None


def _resolve_project(project_path: str) -> Path:
    """解析项目路径，支持绝对路径、相对路径、或项目名模糊匹配。"""
    p = Path(project_path)
    if p.is_absolute():
        return p
    # 先精确查找
    exact = DEFAULT_PROJECTS_DIR / project_path
    if exact.exists():
        return exact
    # 模糊匹配
    found = _find_project(project_path)
    if found:
        return found
    # fallback
    return DEFAULT_PROJECTS_DIR / project_path


# ── Tools ────────────────────────────────────────────────

@server.tool()
async def list_formats() -> str:
    """列出所有可用的画布格式（尺寸/比例/用途）。返回 JSON。"""
    result = _run_script("config.py", ["list-formats"])
    if result["exit_code"] != 0:
        return f"错误：{result['stderr']}"
    return result["stdout"]


@server.tool()
async def list_templates(
    kind: str = "all",
) -> str:
    """列出可用的品牌/风格/布局/Deck 模板。

    Args:
        kind: 模板类型，可选 brand/style/layout/deck/all
    """
    index_map = {
        "brand": "templates/brands/brands_index.json",
        "style": "templates/styles/styles_index.json",
        "layout": "templates/layouts/layouts_index.json",
        "deck": "templates/decks/decks_index.json",
    }
    kinds = [kind] if kind != "all" else list(index_map.keys())
    output = {}
    for k in kinds:
        idx_path = PPT_MASTER_DIR / index_map[k]
        if idx_path.exists():
            output[k] = json.loads(idx_path.read_text(encoding="utf-8"))
        else:
            output[k] = {"error": f"索引文件不存在：{idx_path}"}
    return json.dumps(output, ensure_ascii=False, indent=2)


@server.tool()
async def init_project(
    project_name: str,
    format: str = "ppt169",
) -> str:
    """创建一个新的 PPT 项目。返回实际项目路径。

    Args:
        project_name: 项目名称
        format: 画布格式，如 ppt169(1280×720)、ppt43(1024×768)、xiaohongshu、story 等
    """
    result = _run_script("project_manager.py", [
        "init", project_name, "--format", format,
        "--dir", str(DEFAULT_PROJECTS_DIR),
    ])
    if result["exit_code"] != 0:
        return f"创建失败：{result['stderr'] or result['stdout']}"

    # 从输出中提取实际项目路径
    match = re.search(r"Project (?:created|initialized): (.+)", result["stdout"])
    actual_path = match.group(1).strip() if match else str(DEFAULT_PROJECTS_DIR / project_name)

    return (
        f"项目已创建：{actual_path}\n"
        f"格式：{format}\n"
        f"下一步：调用 create_scaffold 生成 spec 脚手架，然后用 write_svg_page 写幻灯片。"
    )


@server.tool()
async def create_scaffold(
    project_path: str,
    title: str = "演示文稿",
    audience: str = "团队成员",
    objective: str = "展示内容",
    style: str = "consulting",
) -> str:
    """为项目生成 spec 脚手架文件（design_spec.md + spec_lock.md），填充实际值。

    质量检查和导出必须有这两个文件。在写 SVG 之前调用一次。

    Args:
        project_path: 项目路径或项目名
        title: 演示文稿标题
        audience: 目标受众
        objective: 演示目的
        style: 视觉风格（consulting/general/tech/academic）
    """
    p = _resolve_project(project_path)
    if not p.exists():
        return f"项目不存在：{p}。请先调用 init_project。"

    design_spec = f"""# Design Spec

## I. Production Settings
- Canvas: ppt169 (1280×720)
- Speaker Notes: disabled
- Custom Animations: disabled
- Narration Audio: disabled

## II. Communication
- Audience: {audience}
- Objective: {objective}
- Core message: {title}

## III. Visual Style
- Mode: flat
- Style: {style}

## IV. Color Palette
- Background: #FFFFFF
- Primary: #005587
- Accent: #F5A623
- Text: #1A252F

## V. Typography
- Title: sans-serif, 36px
- Body: sans-serif, 18px

## VI. Icons
- Library: none

## VII. Data Visualizations
- None

## VIII. Image Resource List
- None

## IX. Content Outline

### Part 1: Main

#### Slide 01 - Cover

- **Audience move**: 未了解 → 了解
- **Layout**: 居中标题
- **Title**: {title}
- **Core message**: {title}
- **Content**: 标题页
"""

    spec_lock = f"""<!-- ppt-master-schema: spec-lock/v1 -->
# Execution Lock

## canvas
- viewBox: 0 0 1280 720
- format: ppt169

## communication
- primary_language: zh-CN
- audience: {audience}
- objective: {objective}
- core_message: {title}
- consumption_mode: present

## mode
- mode: flat

## visual_style
- visual_style: {style}

## colors
- bg: #FFFFFF
- primary: #005587
- accent: #F5A623
- text: #1A252F

## typography
- font_family: sans-serif
- title_family: sans-serif
- body_family: sans-serif
- body: 18
- title: 36

## icons
- library: none
- inventory: none

## page_rhythm
- P01: cover

## pptx_structure
- mode: flat

## content_outline
- P01: Cover — {title}

## forbidden
- `mask`, `<style>`, `class`, external CSS, `<foreignObject>`, `textPath`, `@font-face`, `<animate*>`, `<set>`, `<script>` / event attributes, `<iframe>`
- HTML named entities in text; write typography as raw Unicode and escape XML reserved characters
"""

    (p / "design_spec.md").write_text(design_spec, encoding="utf-8")
    (p / "spec_lock.md").write_text(spec_lock, encoding="utf-8")

    return f"✅ 已生成 spec 文件：\n- {p / 'design_spec.md'}\n- {p / 'spec_lock.md'}\n现在可以用 write_svg_page 写 SVG 页面了。"


@server.tool()
async def import_sources(
    project_path: str,
    source_files: str,
) -> str:
    """将素材文件导入项目（PDF/DOCX/MD/URL 等）。

    Args:
        project_path: 项目路径或项目名
        source_files: 素材文件路径，多个用逗号分隔
    """
    p = _resolve_project(project_path)
    if not p.exists():
        return f"项目不存在：{p}"

    files = [f.strip() for f in source_files.split(",") if f.strip()]
    if not files:
        return "未提供素材文件。"

    result = _run_script("project_manager.py", ["import-sources", str(p)] + files)
    if result["exit_code"] != 0:
        return f"导入失败：{result['stderr'] or result['stdout']}"
    return f"素材导入成功。\n{result['stdout'][-2000:]}"


@server.tool()
async def write_svg_page(
    project_path: str,
    page_number: int,
    svg_content: str,
) -> str:
    """将一页 SVG 幻灯片写入项目 svg_output/ 目录。

    SVG 必须满足 ppt-master 规范：
    - 根元素有 viewBox 属性
    - 每个根 <g> 有 data-pptx-bounds="x y w h"
    - 背景 <rect> 有 id + data-pptx-role="background"
    - 不用 <style>、class、foreignObject 等

    Args:
        project_path: 项目路径或项目名
        page_number: 页码（从 1 开始）
        svg_content: 完整的 SVG 内容字符串
    """
    p = _resolve_project(project_path)
    svg_dir = p / "svg_output"
    svg_dir.mkdir(parents=True, exist_ok=True)

    filename = f"P{page_number:02d}.svg"
    svg_file = svg_dir / filename
    svg_file.write_text(svg_content, encoding="utf-8")

    return f"已保存：{svg_file}\n页面大小：{len(svg_content)} 字符"


@server.tool()
async def quality_check(
    project_path: str,
    stage: str = "final",
) -> str:
    """对 SVG 页面运行质量检查。

    Args:
        project_path: 项目路径或项目名
        stage: 检查阶段，first-page（首页面检查）或 final（全部页面检查）
    """
    p = _resolve_project(project_path)
    if not p.exists():
        return f"项目不存在：{p}"

    result = _run_script("svg_quality_checker.py", [str(p), "--stage", stage, "--json"])
    output = result["stdout"] + "\n" + result["stderr"]
    if result["exit_code"] == 0:
        return f"✅ 质量检查通过。\n{output[-2000:]}"
    else:
        return f"❌ 质量检查未通过（exit={result['exit_code']}）。\n{output[-2000:]}"


@server.tool()
async def export_pptx(
    project_path: str,
    no_notes: bool = True,
) -> str:
    """将 svg_output/ 中的 SVG 页面导出为 PPTX 文件。

    Args:
        project_path: 项目路径或项目名
        no_notes: 是否跳过演讲者备注（默认 True = 不生成备注）
    """
    p = _resolve_project(project_path)
    if not p.exists():
        return f"项目不存在：{p}"

    svg_dir = p / "svg_output"
    if not svg_dir.exists() or not any(svg_dir.glob("*.svg")):
        return "svg_output/ 中没有 SVG 文件。请先用 write_svg_page 写入幻灯片。"

    args = [str(p)]
    if no_notes:
        args.append("--no-notes")

    result = _run_script("svg_to_pptx.py", args)
    if result["exit_code"] != 0:
        return f"导出失败：\n{result['stdout'][-2000:]}\n{result['stderr'][-1000:]}"

    # 找到导出的 pptx 文件
    exports_dir = p / "exports"
    pptx_files = sorted(exports_dir.glob("*.pptx")) if exports_dir.exists() else []
    latest = pptx_files[-1] if pptx_files else "（未找到导出文件）"

    return f"✅ PPTX 导出成功：{latest}\n{result['stdout'][-2000:]}"


@server.tool()
async def get_project_status(
    project_path: str,
) -> str:
    """查看项目的当前状态（已有的 SVG 页面、图片、导出文件等）。

    Args:
        project_path: 项目路径或项目名
    """
    p = _resolve_project(project_path)
    if not p.exists():
        return f"项目不存在：{p}"

    status = {"project": str(p), "exists": True}

    for subdir in ["svg_output", "svg_final", "images", "exports", "sources", "notes"]:
        d = p / subdir
        if d.exists():
            files = list(d.iterdir())
            status[subdir] = {"count": len(files), "files": [f.name for f in sorted(files)[:20]]}
        else:
            status[subdir] = {"count": 0, "files": []}

    for f in ["design_spec.md", "spec_lock.md"]:
        status[f] = (p / f).exists()

    return json.dumps(status, ensure_ascii=False, indent=2)


@server.tool()
async def read_svg_page(
    project_path: str,
    page_number: int,
) -> str:
    """读取已保存的 SVG 页面内容（用于修改后重新保存）。

    Args:
        project_path: 项目路径或项目名
        page_number: 页码
    """
    p = _resolve_project(project_path)
    svg_file = p / "svg_output" / f"P{page_number:02d}.svg"
    if not svg_file.exists():
        return f"页面不存在：{svg_file}"
    return svg_file.read_text(encoding="utf-8")




# ── 设计参考文档工具 ────────────────────────────────────────

# 核心设计参考（写 SVG 必读）
CORE_REFERENCES = {
    "shared-standards-core": "references/shared-standards-core.md",
    "svg-effects": "references/svg-effects.md",
    "native-shape-authoring": "references/native-shape-authoring.md",
    "semantic-svg": "references/semantic-svg.md",
    "executor-base": "references/executor-base.md",
    "canvas-formats": "references/canvas-formats.md",
    "image-base": "references/image-base.md",
    "image-generator": "references/image-generator.md",
}


@server.tool()
async def list_references() -> str:
    """列出可用的 SVG 设计参考文档。写 SVG 之前应先读取相关参考。"""
    refs = []
    for name, rel_path in CORE_REFERENCES.items():
        full = Path(__file__).resolve().parent / "references" / Path(rel_path).name
        if full.exists():
            size_kb = full.stat().st_size // 1024
            refs.append(f"  {name} ({size_kb}KB)")

    # 视觉风格
    styles_dir = Path(__file__).resolve().parent / "references" / "visual-styles"
    if styles_dir.exists():
        styles = [f.stem for f in styles_dir.glob("*.md") if f.stem != "_index"]
        refs.append(f"\n  视觉风格：visual_styles/{'、'.join(styles[:8])}...")

    # 模式
    modes_dir = Path(__file__).resolve().parent / "references" / "modes"
    if modes_dir.exists():
        modes = [f.stem for f in modes_dir.glob("*.md") if f.stem != "_index"]
        refs.append(f"  模式：modes/{'、'.join(modes)}")

    return "可用设计参考文档：\n" + "\n".join(refs) + "\n\n用 get_reference(name) 读取具体内容。"


@server.tool()
async def get_reference(name: str) -> str:
    """读取设计参考文档。写 SVG 前必须阅读相关参考以保证质量。

    Args:
        name: 参考文档名称，如 shared-standards-core、svg-effects、executor-base 等
              也支持 visual_styles/xxx 或 modes/xxx 格式
    """
    # 核心参考
    if name in CORE_REFERENCES:
        path = Path(__file__).resolve().parent / "references" / CORE_REFERENCES[name].split("/",1)[-1]
    # 视觉风格
    elif name.startswith("visual_styles/"):
        style_name = name.split("/", 1)[1]
        path = Path(__file__).resolve().parent / "references" / "visual-styles" / f"{style_name}.md"
    elif name.startswith("modes/"):
        mode_name = name.split("/", 1)[1]
        path = Path(__file__).resolve().parent / "references" / "modes" / f"{mode_name}.md"
    else:
        path = Path(__file__).resolve().parent / "references" / f"{name}.md"

    if not path.exists():
        return f"参考文档不存在：{name}\n用 list_references 查看可用列表。"

    content = path.read_text(encoding="utf-8")
    # 截断过长的文档
    if len(content) > 15000:
        content = content[:15000] + "\n\n... [文档过长，已截断] ..."
    return content


@server.tool()
async def get_svg_example(
    kind: str = "cover",
    style: str = "consulting",
) -> str:
    """获取一个高质量 SVG 模板示例，作为写新页面的参考。

    Args:
        kind: 页面类型（cover/section/body）
        style: 视觉风格（consulting/general/tech/academic）
    """
    colors = {
        "consulting": {"primary": "#005587", "accent": "#F5A623", "bg": "#FFFFFF", "text": "#1A252F"},
        "tech": {"primary": "#00D1FF", "accent": "#00FF88", "bg": "#0A0E17", "text": "#FFFFFF"},
        "general": {"primary": "#2196F3", "accent": "#FF9800", "bg": "#FFFFFF", "text": "#2C3E50"},
        "academic": {"primary": "#8B0000", "accent": "#C9B037", "bg": "#FFFFFF", "text": "#1A1A1A"},
    }
    c = colors.get(style, colors["consulting"])

    if kind == "cover":
        svg = (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" data-pptx-page-role="cover">\n'
            '  <g id="background" data-pptx-bounds="0 0 1280 720">\n'
            f'    <rect id="bg-fill" width="1280" height="720" fill="{c["primary"]}" data-pptx-role="background"/>\n'
            f'    <rect id="accent-bar" x="0" y="680" width="1280" height="4" fill="{c["accent"]}"/>\n'
            '  </g>\n'
            '  <g id="title-block" data-pptx-bounds="100 180 1080 360">\n'
            '    <text id="title" x="640" y="320" text-anchor="middle" font-size="52" font-family="Microsoft YaHei, sans-serif" fill="#FFFFFF" font-weight="bold">标题文字</text>\n'
            f'    <text id="subtitle" x="640" y="400" text-anchor="middle" font-size="24" font-family="Microsoft YaHei, sans-serif" fill="{c["accent"]}">副标题文字</text>\n'
            '    <text id="date" x="640" y="480" text-anchor="middle" font-size="16" font-family="Microsoft YaHei, sans-serif" fill="rgba(255,255,255,0.6)">2026年8月</text>\n'
            '  </g>\n'
            '</svg>'
        )
    elif kind == "section":
        svg = (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" data-pptx-page-role="section">\n'
            '  <g id="background" data-pptx-bounds="0 0 1280 720">\n'
            f'    <rect id="bg-fill" width="1280" height="720" fill="{c["bg"]}" data-pptx-role="background"/>\n'
            '  </g>\n'
            '  <g id="section-header" data-pptx-bounds="100 260 1080 200">\n'
            f'    <rect id="accent-line" x="100" y="280" width="60" height="4" fill="{c["accent"]}"/>\n'
            f'    <text id="section-title" x="100" y="340" font-size="44" font-family="Microsoft YaHei, sans-serif" fill="{c["primary"]}" font-weight="bold">章节标题</text>\n'
            '    <text id="section-desc" x="100" y="400" font-size="20" font-family="Microsoft YaHei, sans-serif" fill="#888888">章节描述文字</text>\n'
            '  </g>\n'
            '</svg>'
        )
    else:
        svg = (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" data-pptx-page-role="body">\n'
            '  <g id="background" data-pptx-bounds="0 0 1280 720">\n'
            f'    <rect id="bg-fill" width="1280" height="720" fill="{c["bg"]}" data-pptx-role="background"/>\n'
            '  </g>\n'
            '  <g id="header" data-pptx-bounds="60 30 1160 80">\n'
            f'    <rect id="header-accent" x="60" y="50" width="4" height="40" fill="{c["accent"]}"/>\n'
            f'    <text id="page-title" x="80" y="80" font-size="32" font-family="Microsoft YaHei, sans-serif" fill="{c["primary"]}" font-weight="bold">页面标题</text>\n'
            '  </g>\n'
            '  <g id="content" data-pptx-bounds="60 130 1160 550">\n'
            '    <g id="card-1" data-pptx-bounds="80 150 520 200">\n'
            '      <rect id="card-1-bg" x="80" y="150" width="520" height="200" rx="8" fill="#F5F5F5"/>\n'
            f'      <text id="card-1-title" x="100" y="190" font-size="20" font-family="Microsoft YaHei, sans-serif" fill="{c["primary"]}" font-weight="bold">要点一</text>\n'
            f'      <text id="card-1-body" x="100" y="230" font-size="16" font-family="Microsoft YaHei, sans-serif" fill="{c["text"]}">详细内容描述</text>\n'
            '    </g>\n'
            '    <g id="card-2" data-pptx-bounds="680 150 520 200">\n'
            '      <rect id="card-2-bg" x="680" y="150" width="520" height="200" rx="8" fill="#F5F5F5"/>\n'
            f'      <text id="card-2-title" x="700" y="190" font-size="20" font-family="Microsoft YaHei, sans-serif" fill="{c["primary"]}" font-weight="bold">要点二</text>\n'
            f'      <text id="card-2-body" x="700" y="230" font-size="16" font-family="Microsoft YaHei, sans-serif" fill="{c["text"]}">详细内容描述</text>\n'
            '    </g>\n'
            '  </g>\n'
            '</svg>'
        )

    return f"规范 SVG 模板（{kind}/{style}）：\n\n{svg}"

# ── 入口 ────────────────────────────────────────────────
def main():
    import argparse
    parser = argparse.ArgumentParser(description="PPT Master MCP Server")
    parser.add_argument("--port", type=int, default=None, help="SSE 端口（不指定则 stdio 模式）")
    args = parser.parse_args()

    if args.port:
        asyncio.run(server.run_sse_async(port=args.port))
    else:
        asyncio.run(server.run_stdio_async())


if __name__ == "__main__":
    main()
