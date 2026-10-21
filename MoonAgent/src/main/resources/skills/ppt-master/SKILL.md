---
name: ppt-master
description: 生成、重构、美化、填充或增强演示文稿时使用：可编辑 PPTX、幻灯片、模板填充、图片转 PPT、课件、旁白/动画。用户提到 PPT、PPTX、幻灯片、演示文稿、课件或 ppt-master 时优先加载。
license: Apache-2.0
metadata:
  moon:
    label: PPT 生成
    model_role: text
    version: "6.7.2"
    upstream: "https://github.com/hugohe3/ppt-master"
allowed-tools: load_skill_through_path read_file write_file edit_file grep_files glob_files list_files execute deliver_artifact run_python_script
---

# PPT 生成（ppt-master）

平台已经按需把完整 ppt-master 技能包物化到本会话工作区，目录固定为：

```text
skills/ppt-master/          # 相对工作区根目录；绝对路径用 pwd 拼出来
```

**先解析路径，再执行任何命令**：

```bash
SKILL_DIR="$(pwd)/skills/ppt-master"
test -f "$SKILL_DIR/SKILL.md" || echo "MISSING"
```

- `SKILL.md` 存在 → 继续下面的流程。
- 不存在 → 如实说明「本会话没有挂载 ppt-master 技能包，请重新发送并保留 PPT 关键词」，
  **不要**搜索其他目录、不要自己重写整套技能逻辑、不要编造脚本路径。

## 执行顺序（严格遵守）

1. **完整性门禁**：运行
   `python3 "$SKILL_DIR/scripts/attribution_guard.py"`。
   非 0 退出即停止，不检查、不修复、不绕过。
2. **读取上游权威说明**：用 `read_file` 读取
   `skills/ppt-master/SKILL.md`（沙箱里的原始文件，不是本文件的副本）。
3. 该文件里所有 `${SKILL_DIR}` 都替换为上面解析出的绝对路径
   （`$(pwd)/skills/ppt-master`），不要 cd、不要假设仓库根目录、不要猜别的路径。
4. 按原始 `SKILL.md` 的强制加载顺序继续：读 `workflows/routing.md`，选择**唯一**一条顶层路线，
   只加载该路线及其显式触发的支撑文档。**如果路线命中 `workflows/stages/live-preview.md`，
   直接跳过该阶段**（原因见下面的硬约束），改用 `output/preview/` 的静态图片交付。
5. 路线确定后，后续的 `workflows/`、`references/`、`templates/`、`scripts/` 文件都从
   `skills/ppt-master/...` 读取；**不要**用 `load_skill_through_path` 逐份加载大文件，
   直接用 `read_file` / `grep_files` / `execute` 访问沙箱里的真实文件。

## 硬约束：没有本地预览地址

沙箱运行在容器内，用户浏览器**访问不到**沙箱里的 `127.0.0.1` / `localhost` / `0.0.0.0` 端口。

- **禁止**启动 `workflows/stages/live-preview.md` 里的本地 HTTP / Flask 预览服务，也不要执行它的启动命令。
- **禁止**在任何回复、计划、确认卡里给出 `http://127.0.0.1:*`、`http://localhost:*`、`0.0.0.0:*` 之类的预览链接。
- 「设计参数确认」只列画布尺寸、页数、配色、字体和交付格式（PPTX/PNG/PDF），
  **不要**列“实时预览”服务、端口或访问地址。
- 需要“预览”时：把每页渲染成 PNG（必要时再合并 PDF）写入 `output/preview/`，连同 PPTX 一起交付；
  系统会把 `output/` 自动发布成可点击附件，用户直接在聊天里看。
- 已经启动的本地服务要停掉；不要等待用户打开链接，也不要解释“手动访问”。

「设计参数确认」按这个口径写（没有“实时预览”行）：

```markdown
沟通目标：…
画布：1280×720（16:9）｜正文 30px｜共 N 页
配色：底 #… / 面板 #… / 主强调 #… / 次强调 #…
字体：标题与正文 …；数字 …
交付：output/xxx.pptx + output/preview/page-01.png …
```

## Moon 运行环境约定

- Python 解释器：`python3`；工作目录已经是会话工作区根目录。
- 依赖（python-pptx、PyYAML、XlsxWriter、Pillow、numpy、lxml、skia-pathops、uharfbuzz 等）
  已预装在沙箱镜像；缺依赖时如实报告，不要 `pip install`（沙箱出站受限）。
- 最终交付物必须写入 `output/`（PPTX、图片、Markdown、音频等）；每轮结束系统会自动扫描
  `output/` 并发布为可点击附件。不要只把成品写在 `skills/` 或 `work/` 里。
- 单个产物上限 50MB；超限时先压缩或拆分，并在最终回复说明。
- 技能包是只读基线：不要修改 `skills/ppt-master/` 里的脚本/模板来「绕过」报错；
  中间文件写到 `work/`，成品写到 `output/`。

## 用户可见输出

- 使用用户当前语言。
- 第一次进入具体角色前，按上游要求输出角色切换块（Role Switch / 角色切换）。
- 最终回复只说明实际生成的文件名、页数/路线和用途；不要展示内部 ID、绝对路径或工具参数。
- 最终回复**不要**出现本地预览地址（见上面的硬约束）；预览以 `output/` 里的图片/PDF 附件为准。
- 遇到 `⛔ BLOCKING` 门禁必须停下来等用户确认，不得代替用户做决定。
