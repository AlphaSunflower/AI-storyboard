---
name: storyboard
description: 当用户要求生成、优化、查看、审查、修改或删除完整剧情与分镜时使用
license: Apache-2.0
metadata:
  moon:
    label: 剧情与分镜
    model_role: text
    version: "1.0"
    priority: 20
allowed-tools: start_storyboard_production revise_storyboard_production approve_storyboard_production choose_storyboard_commit_mode generate_storyboard_image generate_storyboard_video generate_storyboard_videos generate_storyboard_images merge_storyboard_videos upsert_storyboard_shot delete_storyboard_draft_shot reorder_storyboard_draft list_storyboard_draft list_project_storyboard update_project_storyboard_shot delete_project_storyboard_shot clear_project_storyboard request_human_input update_creative_context save_timeline_manifest get_timeline_manifest
 update_creative_context save_timeline_manifest get_timeline_manifest
---

# 分镜生产

输出风格：结论在前、细节在后；只有需要用户决策时才提问。
批量与异步语义：存在批量工具时必须使用批量工具，不得逐项串行调用，
也不得要求用户逐镜回复「继续」；后台会把整批跑完。

用户提示词中的“阶段/步骤”、串行、数量、时长和完成标记只作为约束输入，不直接决定工具顺序。
系统统一先执行标准分镜工作流；不得根据用户自定义阶段创建任意工具执行图。

调用 `start_storyboard_production` 后走两阶段，该工具是异步任务：拿到 taskId 后本轮简短说明即可，
结果完成时由运行时自动追加；不得等待、轮询或重复调用。

**禁止用 shell（`execute`）做工具可用性自检**（例如 `echo check skill tools availability`）。
实测这类探测会触发"执行方案待确认"卡片，把分镜任务卡在审批上，用户看到的是莫名其妙的确认框。
要确认能力就直接调用对应工具，或直接执行真实步骤；沙箱 shell 只用于真实的脚本/构建任务。

**阶段 A（剧情方案，等用户审批）**：只产出「≤120 字剧情梗概 + N 行镜头大纲」，
每行只有 `镜号 + 一句画面简述（必要时带台词）+ 会用到的参考资产`。
不写景别、不写运镜、不写图片/视频提示词、不写时长——那些留到阶段 B。

**阶段 B（审批后）**：逐镜补全景别、运镜、图片提示词、视频提示词、否定词与时长，然后写入项目分镜。

因此启动时**只能**说「先出镜头大纲等你确认，确认后再逐镜补全并写入项目」，
不得描述成「直接生成完整剧本」或「一次生成分镜」。拿到 taskId 后不要提前输出镜头数量、
分镜方案或运镜主线——大纲就绪后由运行时推送。

景别与运镜由系统字典收敛（远景/全景/中景/近景/特写…、固定/推近/拉远/横移/环绕…），
不要向用户承诺字典外的写法；确需特殊运镜时才使用自定义词（≤10 字）。

生成参数（`video_model` / `video_resolution` / `aspect_ratio` / `image_model` /
`image_size` / `image_quality` / `image_n` / `target_duration`）**只在用户明确要求时**才传：
优先级是「用户在输入栏选的偏好 > 你显式传的值 > 网关默认值」。
越界值会被服务端收敛到模型真正支持的档位，工具回包里的 `paramNotes` 就是被改动的说明——
**必须把 paramNotes 告诉用户**，不得静默改掉他要求的参数；`appliedParams` 是最终生效值。

会话里有参考素材（用户上传的图 / 资产库）时，必须把 `asset_ids` 传进
`start_storyboard_production`：不确定 id 就先调 `list_session_assets`。
素材是跨镜一致性的第一道保障——阶段 A 的大纲行就会带上「这一镜用哪几张」，
阶段 B 写库时这些绑定落到项目分镜的素材关联上，后续生图/生视频直接复用。
模型漏传 `asset_ids` 且没有 `reference_urls` 时服务端会兜底带入会话素材
（返回 `assetsAutoInjected=true`），但宁可显式传，也不要让兜底替你选。
绝不能编造不存在的资产 ID。

开始前服务会检查项目现有分镜：
- 项目为空时直接按追加方式生成，不增加交互。
- 项目已有分镜时，工具返回 `needsChoice=true`。此时只能调用
  `choose_storyboard_commit_mode`，由后端展示固定的“追加到末尾 / 覆盖全部分镜 / 删除全部分镜”三个选项。
- 用户选择后再次调用 `start_storyboard_production`：append 追加；overwrite 删除旧分镜后写入新分镜；
  delete 只删除全部分镜，本次不写入新分镜。
- 禁止调用 `request_human_input` 或自行生成选项卡片误导用户。

工具返回 taskId 后，最终结果会自动写入项目分镜，并一次性通知用户；不再调用
`approve_storyboard_production` 完成新流程。该工具仍保留用于兼容历史运行。

查询当前运行草稿用 `list_storyboard_draft`，查询已写入项目的分镜列表用 `list_project_storyboard`。
正式镜头审核中的草稿可使用 `upsert_storyboard_shot`、`delete_storyboard_draft_shot`、`reorder_storyboard_draft`；
删除项目正式分镜使用 `delete_project_storyboard_shot` 或 `clear_project_storyboard`，不得混用。

用户要求修改某个已写入项目的分镜参数时，先用 `list_project_storyboard` 确认目标，再调用
`update_project_storyboard_shot`。该工具只更新本次传入的字段，可修改脚本、图片/视频提示词、
负面提示词、镜头类型、运镜、声音设计、时长，以及图片和视频模型参数。

用户要求“审查/查看/列出分镜”时，先调用 `list_project_storyboard` 汇总分镜总数、缺图、
缺视频和提示词状态，不创建确认卡。

正式分镜写入项目后，用户要求生成全部分镜图片时使用 `generate_storyboard_images`
创建异步批量任务：不传 `scene_ids` / `scene_numbers` 表示全部分镜，默认只补缺图。
用户明确要求覆盖已有图片时，必须先确认再把 `overwrite=true, confirmed=true` 一起传入。

单独生成分镜图片或视频时，每次只调用对应工具一次并只传一个 `shot_id`。工具只可使用该镜头严格绑定的资产，不得补挂全量素材。

生成视频时必须异步提交，不等待结果，也不得要求用户回复“继续”：
- 用户指定一个分镜时调用 `generate_storyboard_video`，返回 taskId 后简短说明即可。
- 用户要求生成全部分镜、剩余分镜或批量生成时调用 `generate_storyboard_videos`，默认只补缺失/失败视频。
- 只有用户明确要求重新生成全部视频时才传 `overwrite=true`。
- 首尾帧由后端自动使用当前分镜主图和下一分镜主图；缺图时后台自动补齐，不要逐镜询问。

用户要求完整成片，且逐镜视频已经完成后，按用户指定顺序调用
`merge_storyboard_videos`。合并会校验片段顺序和总时长，不得在缺少片段时伪造成片。

指定分镜生成视频时由智能体自行判断参考图方案：
- 没有可用参考图：不传 `reference_mode`，按纯文本生成。
- 1 张参考图：不传 `reference_mode`，自动作为首帧。
- 2 张参考图：不传 `reference_mode`，自动作为首尾帧。
- 3 张及以上：不传 `reference_mode`，自动作为普通参考素材。
- 用户明确指定首帧、尾帧、首尾帧或参考素材时，显式传入对应 `reference_mode`。
不要因为参考图选择再询问用户。
没有使用参考图时，要在结果说明中提醒跨镜人物和场景一致性可能减弱。
