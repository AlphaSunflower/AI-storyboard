import { useEffect, useState } from 'react';
import {
  CheckCircle2,
  CircleAlert,
  CircleStop,
  Clock3,
  ImageIcon,
  LoaderCircle,
  MessageSquarePlus,
  RotateCcw,
  XCircle,
} from 'lucide-react';
import { agentApi, type AgentStoryboardRun, type StoryboardChange } from '../../api/agent';
import { storyboardRunIdFromTask, useAgentStore, taskStatusLabel } from '../../stores/agentStore';
import { TaskDocumentActions } from './TaskDocumentActions';
import './AgentTaskCard.css';

export function AgentTaskCard({ taskId }: { taskId: string }) {
  const task = useAgentStore((state) => state.tasks[taskId]);
  const openTaskDrawer = useAgentStore((state) => state.openTaskDrawer);
  const cancelTask = useAgentStore((state) => state.cancelTask);
  const retryTask = useAgentStore((state) => state.retryTask);
  const followUpTask = useAgentStore((state) => state.followUpTask);
  const [followUpOpen, setFollowUpOpen] = useState(false);
  const [instruction, setInstruction] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [approvingPlot, setApprovingPlot] = useState(false);
  // 确认剧情成功后隐藏按钮：旧任务卡片会一直停在 waiting_review，重复点击只会提交过期阶段。
  const [approvedPlot, setApprovedPlot] = useState(false);
  const [retrying, setRetrying] = useState(false);
  // 阶段 A 的审阅数据（剧情梗概 + 镜头大纲）。没有它用户只能在盲批「确认剧情」。
  const [outline, setOutline] = useState<AgentStoryboardRun | null>(null);
  const [outlineLoading, setOutlineLoading] = useState(false);
  // 大纲行内联编辑：审批前直接改一句画面，比「驳回重跑」便宜得多
  const [editingShotId, setEditingShotId] = useState('');
  const [draftAction, setDraftAction] = useState('');
  const [savingEdit, setSavingEdit] = useState(false);
  const [editError, setEditError] = useState('');
  /**
   * 本次审阅期间的改动（before → after）。
   *
   * <p>只记「审批前对这一版大纲做的手工改动」，所以放前端内存即可：
   * 刷新后大纲本身就是最新的，没有"未决变更"需要恢复。
   * 智能体侧产生的变更需要持久化改动日志，那是下一步（要新表 + apply 通道）。
   */
  const [localEdits, setLocalEdits] = useState<
    { shotId: string; ordinal: number; before: string; after: string }[]
  >([]);
  /** 已落库的改动日志（刷新前/其他会话改的），与 localEdits 合并展示 */
  const [persistedChanges, setPersistedChanges] = useState<StoryboardChange[]>([]);

  const plotReviewRunId = task?.status === 'completed' && task.stage === 'waiting_review'
    ? storyboardRunIdFromTask(task) : '';

  const beginEdit = (shotId: string, action: string) => {
    setEditingShotId(shotId);
    setDraftAction(action);
    setEditError('');
  };

  const cancelEdit = () => {
    setEditingShotId('');
    setDraftAction('');
    setEditError('');
  };

  /**
   * 保存单行大纲：整份草稿按 version 原子提交（后端 replaceDraft 会校验版本，
   * 冲突时返回 40901，提示刷新而不是覆盖别人的编辑）。
   */
  const saveEdit = async () => {
    if (!outline || !editingShotId || savingEdit) return;
    const nextAction = draftAction.trim();
    if (!nextAction) {
      setEditError('画面描述不能为空');
      return;
    }
    setSavingEdit(true);
    setEditError('');
    const target = outline.pseudoShots.find((shot) => shot.shotId === editingShotId);
    const before = target?.action ?? '';
    try {
      const pseudoShots = outline.pseudoShots.map((shot) => (
        shot.shotId === editingShotId ? { ...shot, action: nextAction } : shot
      ));
      const response = await agentApi.saveStoryboardDraft(outline.runId, {
        expectedVersion: outline.version,
        pseudoShots,
        finalShots: outline.finalShots ?? [],
      });
      setOutline(response.data.data ?? { ...outline, pseudoShots });
      // 变更审阅：同一镜多次修改只保留第一次的 before（还原时才能回到原始表述）
      setLocalEdits((edits) => {
        const existing = edits.find((entry) => entry.shotId === editingShotId);
        const entry = {
          shotId: editingShotId,
          ordinal: target?.shotOrdinal ?? 0,
          before: existing ? existing.before : before,
          after: nextAction,
        };
        const rest = edits.filter((item) => item.shotId !== editingShotId);
        return before === nextAction ? rest : [...rest, entry].sort((a, b) => a.ordinal - b.ordinal);
      });
      cancelEdit();
    } catch {
      setEditError('保存失败：大纲可能已被更新，请刷新后重试');
    } finally {
      setSavingEdit(false);
    }
  };

  /**
   * 撤销一次大纲改动：把这一镜还原成改动前的表述并重新提交。
   * 与 saveEdit 共用同一条 replaceDraft 通道（版本校验、40901 冲突处理一致）。
   */
  const revertEdit = async (shotId: string) => {
    if (!outline || savingEdit) return;
    const entry = localEdits.find((item) => item.shotId === shotId);
    if (!entry) return;
    setSavingEdit(true);
    setEditError('');
    try {
      const pseudoShots = outline.pseudoShots.map((shot) => (
        shot.shotId === shotId ? { ...shot, action: entry.before } : shot
      ));
      const response = await agentApi.saveStoryboardDraft(outline.runId, {
        expectedVersion: outline.version,
        pseudoShots,
        finalShots: outline.finalShots ?? [],
      });
      setOutline(response.data.data ?? { ...outline, pseudoShots });
      setLocalEdits((edits) => edits.filter((item) => item.shotId !== shotId));
    } catch {
      setEditError('撤销失败：大纲可能已被更新，请刷新后重试');
    } finally {
      setSavingEdit(false);
    }
  };

  useEffect(() => {
    if (!plotReviewRunId || approvedPlot) return;
    let cancelled = false;
    setOutlineLoading(true);
    agentApi.getStoryboardRun(plotReviewRunId)
      .then((response) => {
        if (!cancelled) setOutline(response.data.data ?? null);
      })
      .catch(() => {
        // 大纲拉取失败不阻断审批：退化成只有按钮 + 文字结论
        if (!cancelled) setOutline(null);
      })
      .finally(() => {
        if (!cancelled) setOutlineLoading(false);
      });
    // 已落库的改动（其他会话/刷新前改的）：与本次会话的 localEdits 合并展示
    agentApi.getStoryboardChanges(plotReviewRunId)
      .then((response) => {
        if (!cancelled) setPersistedChanges(response.data.data ?? []);
      })
      .catch(() => {
        // 改动日志读不到不影响审阅主流程
        if (!cancelled) setPersistedChanges([]);
      });
    return () => {
      cancelled = true;
    };
  }, [plotReviewRunId, approvedPlot]);

  if (!task) {
    return (
      <div className="agent-task-card is-missing" data-task-id={taskId}>
        <Clock3 size={15} />
        <span>任务信息同步中</span>
      </div>
    );
  }

  const active = task.status === 'queued' || task.status === 'running'
    || task.status === 'waiting_parent' || task.status === 'cancelling';
  const progress = Math.max(0, Math.min(100, task.progress ?? 0));
  const StatusIcon = task.status === 'completed' ? CheckCircle2
    : task.status === 'partial' ? CircleAlert
    : task.status === 'failed' || task.status === 'cancelled' ? XCircle
    : LoaderCircle;

  const submitFollowUp = async () => {
    if (!instruction.trim() || submitting) return;
    setSubmitting(true);
    try {
      await followUpTask(task.taskId, instruction.trim());
      setInstruction('');
      setFollowUpOpen(false);
    } finally {
      setSubmitting(false);
    }
  };

  const approvePlot = async () => {
    if (!plotReviewRunId || approvingPlot) return;
    setApprovingPlot(true);
    try {
      const response = await agentApi.approveStoryboard(plotReviewRunId, {
        stage: 'PLOT_REVIEW',
        decision: 'APPROVE',
      });
      setApprovedPlot(true);
      if (response.data.data.taskId) openTaskDrawer(response.data.data.taskId);
    } finally {
      setApprovingPlot(false);
    }
  };

  // 重试会新建一个子任务；不加锁重复点击会并发跑两份，长任务/写库会被执行两次。
  const doRetry = async () => {
    if (retrying) return;
    setRetrying(true);
    try {
      await retryTask(task.taskId);
    } finally {
      setRetrying(false);
    }
  };

  return (
    <div
      className={`agent-task-card is-${task.status}`}
      data-task-id={task.taskId}
      role="group"
      aria-label={`后台任务：${task.title}`}
    >
      <button
        type="button"
        className="agent-task-card__main"
        onClick={() => openTaskDrawer(task.taskId)}
      >
        <span className="agent-task-card__icon">
          <StatusIcon size={17} strokeWidth={1.9} className={active ? 'is-spinning' : undefined} />
        </span>
        <span className="agent-task-card__copy">
          <strong>{task.title}</strong>
          <span>{taskStatusLabel(task)}</span>
          {task.resultSummary && <small>{task.resultSummary}</small>}
        </span>
        <span className="agent-task-card__version">v{task.version}</span>
      </button>

      {progress > 0 && progress < 100 && (
        <div className="agent-task-card__progress" aria-hidden="true">
          <span style={{ width: `${progress}%` }} />
        </div>
      )}

      <TaskDocumentActions task={task} compact />

      {plotReviewRunId && outlineLoading && !outline && (
        <p className="agent-task-card__outline-loading">正在载入镜头大纲…</p>
      )}

      {plotReviewRunId && (outline?.pseudoShots?.length ?? 0) > 0 && (
        <section className="agent-task-card__outline" aria-label="镜头大纲">
          {outline?.selectedScript && (
            <p className="agent-task-card__outline-synopsis">{outline.selectedScript}</p>
          )}
          {localEdits.length > 0 && (
            <section className="agent-task-card__changes" aria-label="本次修改">
              {localEdits.map((edit) => (
                <div key={edit.shotId} className="agent-task-card__change">
                  <span className="agent-task-card__change-index">第 {edit.ordinal} 镜</span>
                  <span className="agent-task-card__change-diff">
                    <del>{edit.before || '（空）'}</del>
                    <span aria-hidden="true"> → </span>
                    <ins>{edit.after}</ins>
                  </span>
                  <button
                    type="button"
                    disabled={savingEdit}
                    onClick={() => void revertEdit(edit.shotId)}
                  >
                    撤销
                  </button>
                </div>
              ))}
            </section>
          )}
          {persistedChanges.filter(
            (change) => !localEdits.some((edit) => edit.shotId === change.shotId),
          ).length > 0 && (
            <section className="agent-task-card__changes is-history" aria-label="改动记录">
              {persistedChanges
                .filter((change) => !localEdits.some((edit) => edit.shotId === change.shotId))
                .map((change) => (
                  <div
                    key={`${change.shotId}-${change.field}-${change.at}`}
                    className="agent-task-card__change"
                  >
                    <span className="agent-task-card__change-index">
                      第 {change.shotOrdinal} 镜 · {change.field}
                    </span>
                    <span className="agent-task-card__change-diff">
                      <del>{change.before || '（空）'}</del>
                      <span aria-hidden="true"> → </span>
                      <ins>{change.after}</ins>
                    </span>
                    <span className="agent-task-card__change-source">
                      {change.source === 'user' ? '你' : change.source === 'agent' ? '智能体' : '系统'}
                    </span>
                  </div>
                ))}
            </section>
          )}
          <ol className="agent-task-card__outline-list">
            {outline!.pseudoShots.map((shot) => (
              <li key={shot.shotId} className="agent-task-card__outline-row">
                <span className="agent-task-card__outline-index">{shot.shotOrdinal}</span>
                {editingShotId === shot.shotId ? (
                  <span className="agent-task-card__outline-editor">
                    <textarea
                      aria-label={`编辑第 ${shot.shotOrdinal} 镜画面`}
                      value={draftAction}
                      rows={2}
                      maxLength={120}
                      onChange={(event) => setDraftAction(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === 'Escape') cancelEdit();
                        if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void saveEdit();
                      }}
                    />
                    <span className="agent-task-card__outline-editor-actions">
                      <button type="button" disabled={savingEdit} onClick={() => void saveEdit()}>
                        {savingEdit ? '保存中' : '保存'}
                      </button>
                      <button type="button" disabled={savingEdit} onClick={cancelEdit}>取消</button>
                    </span>
                    {editError && (
                      <span className="agent-task-card__outline-error">{editError}</span>
                    )}
                  </span>
                ) : (
                  <button
                    type="button"
                    className="agent-task-card__outline-text"
                    aria-label={`编辑第 ${shot.shotOrdinal} 镜画面`}
                    title="点击修改这一镜的画面描述"
                    onClick={() => beginEdit(shot.shotId, shot.action)}
                  >
                    {shot.action || '（未填写画面）'}
                  </button>
                )}
                {shot.referenceAssetIds.length > 0 && (
                  <span
                    className="agent-task-card__outline-assets"
                    title={`参考资产：${shot.referenceAssetIds.join('、')}`}
                  >
                    <ImageIcon size={11} />
                    {shot.referenceAssetIds.length}
                  </span>
                )}
              </li>
            ))}
          </ol>
        </section>
      )}

      <div className="agent-task-card__actions">
        {plotReviewRunId && !approvedPlot && (
          <button
            type="button"
            className="is-primary"
            disabled={approvingPlot}
            onClick={() => void approvePlot()}
          >
            {approvingPlot ? <LoaderCircle size={13} className="is-spinning" /> : <CheckCircle2 size={13} />}
            {approvingPlot ? '提交中' : '确认剧情'}
          </button>
        )}
        {task.canCancel && (
          <button type="button" onClick={() => void cancelTask(task.taskId)}>
            <CircleStop size={13} />
            取消
          </button>
        )}
        {task.canRetry && (
          <button
            type="button"
            disabled={retrying}
            onClick={() => void doRetry()}
          >
            <RotateCcw size={13} className={retrying ? 'is-spinning' : undefined} />
            {retrying ? '提交中' : task.status === 'partial' ? '重试失败项' : '重试'}
          </button>
        )}
        {task.canFollowUp && (
          <button type="button" onClick={() => setFollowUpOpen((value) => !value)}>
            <MessageSquarePlus size={13} />
            补充要求
          </button>
        )}
      </div>

      {followUpOpen && (
        <div className="agent-task-card__followup">
          <textarea
            value={instruction}
            onChange={(event) => setInstruction(event.target.value)}
            placeholder="说明需要补充或修改的内容"
            rows={3}
          />
          <div>
            <button type="button" onClick={() => setFollowUpOpen(false)}>取消</button>
            <button
              type="button"
              className="is-primary"
              disabled={!instruction.trim() || submitting}
              onClick={() => void submitFollowUp()}
            >
              {submitting ? '提交中' : '加入任务队列'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
