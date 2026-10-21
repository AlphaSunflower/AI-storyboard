# 手机版站内通知（2026-10-20）

## 范围

本文件记录手机版「智能体对话 / 项目库 / 我的」三 Tab 之后，站内通知这一层的实现。
定时任务（此前尝试过的 `agent_schedules`）已整体撤除：代码、迁移和本地表都已删除，
不要再按旧设计恢复。

## 数据

`public.user_notifications`（`V51__user_notifications.sql`，需手动执行）：

| 列 | 说明 |
|----|------|
| `id` / `user_id` | 通知与归属账号 |
| `type` | 目前只有 `TASK_COMPLETED` |
| `title` / `body` | 列表展示文案（body 会截断到 2000 字符） |
| `link_type` / `link_id` | 目前固定 `conversation` + 会话 id，点通知直接回到对应对话 |
| `dedupe_key` | 唯一索引（部分索引）；同一任务同一终态重复写入只落一条 |
| `read_at` | 已读时间；未读 = `NULL` |

## 后端

- `NotificationService`（`service/notification`）：`create` / `list` / `unreadCount` /
  `markRead` / `markAllRead`。`create` 捕获 `DuplicateKeyException`，重复事件静默跳过。
- `NotificationController`（`/api/agent/notifications`）：
  - `GET /`（`limit` 默认 50，上限 100）
  - `GET /unread-count`
  - `POST /{id}/read`
  - `POST /read-all`
- 生产者：`AgentScopeAsyncTaskService.publish(taskId)` 在任务进入终态后调用
  `notifyTerminalTask`，覆盖 `completed` / `partial` / `failed`；用户主动 `cancelled` 不通知。
  通知写入失败只告警，不影响任务本身（与任务快照推送同一降级策略）。
- 不新增 SSE 通道：通知走 HTTP 列表 + 轮询，避免和 turn 的有序事件通道混用。

## 前端

- `stores/notificationStore.ts`：`items` / `unread` / `drawerOpen`，标记已读先本地乐观更新。
- `hooks/useNotificationPolling.ts`：进入页面、回到前台、每 60 秒刷新未读数；
  ChatPage 挂载。
- `components/layout/NotificationCenter.tsx`：手机端底部抽屉、桌面端右侧抽屉；
  点条目标记已读并 `navigate('/chat', { state: { conversationId } })`。
- 入口：
  - 手机顶栏铃铛（`MobileHeader`，带角标）
  - 底部「智能体对话」Tab 角标（`MobileBottomNav` 的 `badges`）
  - 桌面会话栏图标（折叠 rail 圆点 / 展开底栏计数）

## 真正的系统推送（未做）

现在的「推送」是站内通知 + 60 秒轮询，锁屏/退到后台时不会弹系统通知。补 Web Push 需要：

1. **VAPID 密钥**（`VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` / subject）——没有密钥就先不启用，
   不要生成后写进代码。
2. `push_subscriptions` 表（endpoint / p256dh / auth / user_id）。
3. `public/sw.js`：`push` 事件 `showNotification`，`notificationclick` 聚焦并打开 `linkId`。
4. 前端订阅按钮必须放在用户手势里（`Notification.requestPermission()` 后
   `pushManager.subscribe`）；iOS 16.4+ 还要求先把站点「添加到主屏幕」。
5. 国内 Android 浏览器不一定能连通 FCM，站内通知必须继续保留为兜底。

在拿到 VAPID 密钥之前，这一层不做半成品：没有密钥的订阅按钮和没有发送端的 service worker
都只会制造「看起来开了但收不到」的假象。

## 验证

```powershell
cd AIStoryboardClient
npm test        # 997 passed
npm run build   # tsc -b + vite build 通过

mvn -pl MoonAgent -am "-Dtest=AgentScopeAsyncTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
# 18 passed
```

本地库已执行 `V51`，并确认 `agent_schedules` / `agent_schedule_runs` 已不存在。
