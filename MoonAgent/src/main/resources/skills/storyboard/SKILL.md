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
allowed-tools: resolve_agent_workflow start_storyboard_production revise_storyboard_production approve_storyboard_production choose_storyboard_commit_mode generate_storyboard_image generate_storyboard_video generate_storyboard_videos generate_storyboard_images merge_storyboard_videos upsert_storyboard_shot delete_storyboard_draft_shot reorder_storyboard_draft list_storyboard_draft list_project_storyboard update_project_storyboard_shot delete_project_storyboard_shot clear_project_storyboard request_human_input
---

# 分镜生产

当用户提示词包含明确的“阶段/步骤”、要求串行、禁止并行、禁止跳阶段，或要求每步输出完成标记时，
必须先调用 `resolve_agent_workflow`。此后严格按它返回的步骤和依赖执行，不得再无条件调用一体化
`start_storyboard_production` 跳过用户步骤。用户未声明自定义流程时，才使用下面的默认自动流程。

调用 `start_storyboard_production` 后，服务会先在后台根据用户需求生成并优化完整剧情，
再连续完成剧情诊断、针对问题的自动修正、正式镜头生成和项目写库。该工具是异步任务：
拿到 taskId 后本轮简短说明即可，结果完成时由运行时
自动追加；不得等待、轮询或重复调用。不要生成或展示“扩写稿”，也不要停在剧情/伪分镜审核。
启动时的说明必须明确是先根据用户需求生成并优化完整剧情，再据此设计结构化分镜；
不得描述成直接生成分镜。拿到 taskId 后只说明这个执行顺序，不得提前输出分镜方案、
镜头数量或运镜主线，完整剧情生成后由运行时推送。

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
