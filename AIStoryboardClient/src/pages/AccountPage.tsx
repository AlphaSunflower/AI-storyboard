import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'motion/react';
import {
  ArrowLeft, Brain, Check, ImagePlus, Inbox, LogOut, MessageSquareHeart, Pencil,
  ReceiptText, ShieldAlert, ShieldCheck, SlidersHorizontal, Trash2, UserRound, Wallet, Zap, X,
} from 'lucide-react';
import {
  userApi,
  type FeedbackAdminItem,
  type FeedbackStatus,
  type UserStats,
} from '../api/user';
import { memoryApi, type AgentMemory } from '../api/memory';
import client from '../api/client';
import { assetUrl } from '../config';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { useAgentStore } from '../stores/agentStore';
import { useAccountSettingsStore } from '../stores/accountSettingsStore';
import { notify } from '../stores/toastStore';
import { FullAccessConfirmDialog } from '../components/agent/FullAccessConfirmDialog';
import type { AgentApprovalMode } from '../api/agent';
import './AccountPage.css';

const KIND_LABEL: Record<string, string> = {
  goal: '目标',
  style: '风格',
  constraint: '约束',
  decision: '决策',
  feedback: '反馈',
};

type SectionId = 'profile' | 'billing' | 'memory' | 'settings' | 'feedback' | 'inbox';

const SECTIONS: Array<{
  id: SectionId;
  label: string;
  description: string;
  icon: typeof UserRound;
}> = [
  { id: 'profile', label: '个人信息', description: '资料、余额与密码', icon: UserRound },
  { id: 'billing', label: '余额明细', description: '充值、消费与退款记录', icon: ReceiptText },
  { id: 'memory', label: '记忆管理', description: 'Moon 记住的项目事实', icon: Brain },
  { id: 'settings', label: '通用设置', description: '权限、发送与展示', icon: SlidersHorizontal },
  { id: 'feedback', label: '帮助与反馈', description: '截图并描述问题', icon: MessageSquareHeart },
  { id: 'inbox', label: '反馈收件箱', description: '查看并处理用户反馈', icon: Inbox },
];

const APPROVAL_OPTIONS: Array<{
  id: AgentApprovalMode;
  label: string;
  description: string;
  icon: typeof ShieldCheck;
}> = [
  {
    id: 'FULL_ACCESS',
    label: '完全权限',
    description: '自动连续执行，不再暂停确认。删除、覆盖等操作也会直接执行。',
    icon: Zap,
  },
  {
    id: 'PLAN_APPROVAL',
    label: '方案批准',
    description: '先展示执行方案，批准后按方案生成。适合大多数创作任务。',
    icon: ShieldCheck,
  },
  {
    id: 'REQUEST_APPROVAL',
    label: '逐项批准',
    description: '每次高风险操作都需要你明确允许，最稳妥但打断最多。',
    icon: ShieldAlert,
  },
];

const BUSY_OPTIONS = [
  {
    id: 'queue' as const,
    label: '排队发送',
    description: '当前回合继续跑完，消息排在后面自动发送。队列会显示在输入框上方，可删除、置顶或调整顺序。',
  },
  {
    id: 'steer' as const,
    label: '插队发送',
    description: '把消息插入当前回合的下一个模型步骤，适合临时补充要求或纠正方向。',
  },
];

const DISPLAY_OPTIONS = [
  {
    id: 'thinking' as const,
    label: '只看见思考',
    description: '只展示思考过程；工具调用、技能使用只保留状态字眼，不显示名称和内容。',
  },
  {
    id: 'standard' as const,
    label: '标准',
    description: '展示思考、Agent 轨迹和工具名称，不展开入参、输出等详细内容。',
  },
  {
    id: 'detailed' as const,
    label: '详细',
    description: '当前状态：展示思考、轨迹、工具名称，并可展开完整入参与输出。',
  },
];

interface FeedbackImage {
  url: string;
  name: string;
}

const MAX_FEEDBACK_IMAGES = 6;

function errorMessage(error: unknown): string {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message || '操作失败';
}

function formatFeedbackTime(iso?: string): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function AccountPage() {
  const navigate = useNavigate();
  const reduceMotion = useReducedMotion();
  const logout = useAuthStore((state) => state.logout);
  const [checking, setChecking] = useState(true);
  const [section, setSection] = useState<SectionId>('profile');
  const [role, setRole] = useState('member');
  const [stats, setStats] = useState<UserStats | null>(null);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [oldPwd, setOldPwd] = useState('');
  const [newPwd, setNewPwd] = useState('');
  const [confirmPwd, setConfirmPwd] = useState('');
  const [memories, setMemories] = useState<AgentMemory[]>([]);
  const [memoryLoading, setMemoryLoading] = useState(true);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editKind, setEditKind] = useState('decision');
  const [editContent, setEditContent] = useState('');
  const [editConfidence, setEditConfidence] = useState('0.8');
  const [settingsSaving, setSettingsSaving] = useState(false);
  const [settingsNotice, setSettingsNotice] = useState('');
  const [confirmFullAccess, setConfirmFullAccess] = useState(false);
  const [feedbackText, setFeedbackText] = useState('');
  const [feedbackImages, setFeedbackImages] = useState<FeedbackImage[]>([]);
  const [feedbackUploading, setFeedbackUploading] = useState(false);
  const [feedbackSubmitting, setFeedbackSubmitting] = useState(false);
  const [feedbackSent, setFeedbackSent] = useState(false);
  const [inbox, setInbox] = useState<FeedbackAdminItem[]>([]);
  const [inboxLoading, setInboxLoading] = useState(false);
  const [inboxUpdatingId, setInboxUpdatingId] = useState<string | null>(null);
  const projects = useProjectStore((state) => state.projects);
  const loadProjects = useProjectStore((state) => state.loadProjects);
  const settings = useAccountSettingsStore((state) => state.settings);
  const saveSettings = useAccountSettingsStore((state) => state.save);

  useEffect(() => {
    void useAuthStore.getState().checkAuth().then((ok) => {
      if (!ok) {
        navigate('/login', { replace: true });
        return;
      }
      setChecking(false);
    });
  }, [navigate]);

  useEffect(() => {
    if (checking) return;
    void userApi.getProfile().then((response) => {
      setName(response.data.data.displayName || '');
      setEmail(response.data.data.email || '');
      setRole(response.data.data.role || 'member');
    }).catch(() => undefined);
    void userApi.getStats().then((response) => setStats(response.data.data)).catch(() => undefined);
    void useAccountSettingsStore.getState().load({ force: true });
    void loadProjects();
    void loadMemories();
  }, [checking, loadProjects]);

  useEffect(() => {
    if (section === 'inbox' && role === 'admin') {
      void loadInbox();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [section, role]);

  const loadInbox = async () => {
    setInboxLoading(true);
    try {
      const response = await userApi.getFeedbackInbox();
      setInbox(response.data.data || []);
    } catch (error) {
      notify({ title: '反馈列表加载失败', description: errorMessage(error), variant: 'error' });
    } finally {
      setInboxLoading(false);
    }
  };

  const setFeedbackStatus = async (item: FeedbackAdminItem, status: FeedbackStatus) => {
    setInboxUpdatingId(item.id);
    try {
      await userApi.updateFeedbackStatus(item.id, status);
      setInbox((current) => current.map((entry) => (
        entry.id === item.id ? { ...entry, status } : entry
      )));
    } catch (error) {
      notify({ title: '更新反馈状态失败', description: errorMessage(error), variant: 'error' });
    } finally {
      setInboxUpdatingId(null);
    }
  };

  const loadMemories = async () => {
    setMemoryLoading(true);
    try {
      const response = await memoryApi.list();
      setMemories(response.data.data || []);
    } catch {
      setMemories([]);
    } finally {
      setMemoryLoading(false);
    }
  };

  const projectName = useMemo(() => {
    const map = new Map(projects.map((project) => [project.id, project.name]));
    return (id: string) => map.get(id) || id;
  }, [projects]);

  const avatarLetter = (name.trim() || email.trim() || 'M').slice(0, 1).toUpperCase();

  const saveProfile = async () => {
    try {
      await userApi.updateProfile({ displayName: name, email });
      notify({ title: '基本信息已保存' });
    } catch (error) {
      notify({ title: '保存失败', description: errorMessage(error), variant: 'error' });
    }
  };

  const savePassword = async () => {
    if (newPwd !== confirmPwd) {
      notify({ title: '两次输入的新密码不一致', variant: 'error' });
      return;
    }
    try {
      await userApi.changePassword({ oldPassword: oldPwd, newPassword: newPwd });
      setOldPwd('');
      setNewPwd('');
      setConfirmPwd('');
      notify({ title: '密码已修改' });
    } catch (error) {
      notify({ title: '修改密码失败', description: errorMessage(error), variant: 'error' });
    }
  };

  const applySetting = async (patch: Parameters<typeof saveSettings>[0]) => {
    setSettingsSaving(true);
    setSettingsNotice('');
    try {
      await saveSettings(patch);
      if (patch.defaultApprovalMode) {
        const agentState = useAgentStore.getState();
        if (!agentState.activeConversationId) {
          useAgentStore.setState({ pendingApprovalMode: patch.defaultApprovalMode });
        } else if (!agentState.streaming && !agentState.waitingHumanInput
          && !agentState.waitingVideoPlan) {
          // 已有当前会话也跟随全局权限；正在生成时只存默认值，避免打断在途回合。
          void agentState.setApprovalMode(patch.defaultApprovalMode, { confirmedFullAccess: true });
        }
      }
      setSettingsNotice('已保存');
      window.setTimeout(() => setSettingsNotice(''), 1600);
    } catch (error) {
      notify({ title: '设置保存失败', description: errorMessage(error), variant: 'error' });
    } finally {
      setSettingsSaving(false);
    }
  };

  const chooseApprovalMode = (mode: AgentApprovalMode) => {
    if (mode === settings.defaultApprovalMode || settingsSaving) return;
    if (mode === 'FULL_ACCESS') {
      setConfirmFullAccess(true);
      return;
    }
    void applySetting({ defaultApprovalMode: mode });
  };

  const startEdit = (memory: AgentMemory) => {
    setEditingId(memory.id);
    setEditKind(memory.kind || 'decision');
    setEditContent(memory.content);
    setEditConfidence(String(memory.confidence ?? 0.8));
  };

  const saveEdit = async (memory: AgentMemory) => {
    const content = editContent.trim();
    if (!content) {
      notify({ title: '记忆内容不能为空', variant: 'error' });
      return;
    }
    try {
      const response = await memoryApi.update(memory.id, {
        kind: editKind,
        content,
        confidence: Number(editConfidence) || 0.8,
        expiresAt: memory.expiresAt || null,
      });
      const updated = response.data.data;
      setMemories((current) => current.map((item) => item.id === updated.id ? updated : item));
      setEditingId(null);
      notify({ title: '记忆已更新' });
    } catch (error) {
      notify({ title: '更新失败', description: errorMessage(error), variant: 'error' });
    }
  };

  const deleteMemory = async (memory: AgentMemory) => {
    if (!window.confirm(`删除这条记忆？\n\n${memory.content}`)) return;
    try {
      await memoryApi.remove(memory.id);
      setMemories((current) => current.filter((item) => item.id !== memory.id));
      notify({ title: '记忆已删除' });
    } catch (error) {
      notify({ title: '删除失败', description: errorMessage(error), variant: 'error' });
    }
  };

  const uploadFeedbackImages = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const remaining = MAX_FEEDBACK_IMAGES - feedbackImages.length;
    if (remaining <= 0) {
      notify({ title: `最多上传 ${MAX_FEEDBACK_IMAGES} 张截图`, variant: 'error' });
      return;
    }
    setFeedbackUploading(true);
    try {
      const uploaded: FeedbackImage[] = [];
      for (const file of Array.from(files).slice(0, remaining)) {
        if (!file.type.startsWith('image/')) {
          notify({ title: '仅支持图片文件', description: file.name, variant: 'error' });
          continue;
        }
        if (file.size > 8 * 1024 * 1024) {
          notify({ title: '单张图片不能超过 8MB', description: file.name, variant: 'error' });
          continue;
        }
        const form = new FormData();
        form.append('file', file);
        try {
          const response = await client.post<{ data: { url: string } }>('/files/upload', form, {
            headers: { 'Content-Type': 'multipart/form-data' },
          });
          uploaded.push({ url: response.data.data.url, name: file.name });
        } catch (error) {
          notify({ title: '截图上传失败', description: `${file.name}：${errorMessage(error)}`, variant: 'error' });
        }
      }
      if (uploaded.length > 0) {
        setFeedbackImages((current) => [...current, ...uploaded].slice(0, MAX_FEEDBACK_IMAGES));
      }
    } catch (error) {
      notify({ title: '截图上传失败', description: errorMessage(error), variant: 'error' });
    } finally {
      setFeedbackUploading(false);
    }
  };

  const submitFeedback = async () => {
    if (feedbackText.trim().length < 5) {
      notify({ title: '请至少写 5 个字描述遇到的问题', variant: 'error' });
      return;
    }
    setFeedbackSubmitting(true);
    try {
      await userApi.submitFeedback({
        content: feedbackText.trim(),
        imageUrls: feedbackImages.map((image) => image.url),
      });
      setFeedbackSent(true);
      setFeedbackText('');
      setFeedbackImages([]);
    } catch (error) {
      notify({ title: '反馈提交失败', description: errorMessage(error), variant: 'error' });
    } finally {
      setFeedbackSubmitting(false);
    }
  };

  const visibleSections = useMemo(
    () => SECTIONS.filter((item) => item.id !== 'inbox' || role === 'admin'),
    [role],
  );
  const openInboxCount = inbox.filter((item) => item.status === 'open').length;

  if (checking) {
    return (
      <div className="account-page account-page--centered">
        <span className="account-loading">正在加载…</span>
      </div>
    );
  }

  return (
    <div className="account-page">
      <div className="account-shell">
        <motion.header
          className="account-header"
          initial={reduceMotion ? false : { opacity: 0, y: -10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
        >
          <button type="button" className="account-back" onClick={() => navigate('/chat')}>
            <ArrowLeft size={15} /> 返回 Moon
          </button>
          <div className="account-header__title">
            <h1>账户与设置</h1>
            <p>管理你的身份、记忆，以及 Moon 的工作方式</p>
          </div>
          <button type="button" className="account-logout" onClick={logout}>
            <LogOut size={15} /> 退出登录
          </button>
        </motion.header>

        <div className="account-layout">
          <nav className="account-nav" aria-label="账户设置菜单">
            {visibleSections.map((item, index) => {
              const Icon = item.icon;
              const active = section === item.id;
              return (
                <motion.button
                  key={item.id}
                  type="button"
                  className={`account-nav__item${active ? ' is-active' : ''}`}
                  aria-current={active ? 'page' : undefined}
                  onClick={() => setSection(item.id)}
                  initial={reduceMotion ? false : { opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{
                    duration: 0.38,
                    delay: 0.06 + index * 0.045,
                    ease: [0.16, 1, 0.3, 1],
                  }}
                  whileHover={reduceMotion ? undefined : { x: 2 }}
                >
                  {active && (
                    <motion.span
                      layoutId="account-nav-active"
                      className="account-nav__pill"
                      transition={reduceMotion
                        ? { duration: 0 }
                        : { type: 'spring', stiffness: 420, damping: 34 }}
                    />
                  )}
                  <span className="account-nav__icon" aria-hidden="true"><Icon size={17} /></span>
                  <span className="account-nav__text">
                    <span className="account-nav__label">{item.label}</span>
                    <span className="account-nav__desc">{item.description}</span>
                  </span>
                  {item.id === 'memory' && memories.length > 0 && (
                    <span className="account-nav__badge">{memories.length}</span>
                  )}
                  {item.id === 'inbox' && openInboxCount > 0 && (
                    <span className="account-nav__badge account-nav__badge--alert">{openInboxCount}</span>
                  )}
                </motion.button>
              );
            })}
            <div className="account-nav__note">
              <Wallet size={14} />
              <span>账号余额功能即将上线</span>
            </div>
          </nav>

          <main className="account-content">
            <motion.div
              key={section}
              className="account-section"
              initial={reduceMotion ? false : { opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.32, ease: [0.16, 1, 0.3, 1] }}
            >
            {section === 'profile' && (
              <div className="account-stack">
                <section className="account-card">
                  <div className="account-identity">
                    <div className="account-avatar" aria-hidden="true">{avatarLetter}</div>
                    <div className="account-identity__text">
                      <h2>{name.trim() || '未命名用户'}</h2>
                      <p>{email || '尚未绑定邮箱'}</p>
                    </div>
                    <span className="account-chip">个人账号</span>
                  </div>

                  <div className="account-stats" aria-label="生成数据统计">
                    {[
                      { value: stats?.imageCount, label: '生成图片' },
                      { value: stats?.videoCount, label: '生成视频' },
                      { value: stats?.projectCount, label: '项目总数' },
                    ].map((item) => (
                      <div className="account-stat" key={item.label}>
                        <strong>{item.value ?? 0}</strong>
                        <span>{item.label}</span>
                      </div>
                    ))}
                  </div>

                  <div className="account-field-grid">
                    <label className="account-field">
                      <span>名称</span>
                      <input value={name} onChange={(event) => setName(event.target.value)} placeholder="你的显示名称" />
                    </label>
                    <label className="account-field">
                      <span>邮箱</span>
                      <input value={email} onChange={(event) => setEmail(event.target.value)} placeholder="name@example.com" />
                    </label>
                  </div>
                  <div className="account-card__actions">
                    <button type="button" className="account-primary" onClick={saveProfile}>保存基本信息</button>
                  </div>
                </section>

                <section className="account-card account-balance">
                  <span className="account-balance__glow" aria-hidden="true" />
                  <div className="account-card__head">
                    <div>
                      <h2>账号余额</h2>
                      <p>后续用于按量计费与充值，当前暂未开放。</p>
                    </div>
                    <Wallet size={20} className="account-card__head-icon" aria-hidden="true" />
                  </div>
                  <div className="account-balance__value">
                    <span className="account-balance__label">可用余额</span>
                    <div className="account-balance__amount">
                      <strong>¥ 0.00</strong>
                      <span className="account-chip account-chip--muted">暂未开通</span>
                    </div>
                  </div>
                  <div className="account-balance__foot">
                    <span>Moon 账户</span>
                    <span>充值功能即将上线</span>
                  </div>
                </section>

                <section className="account-card">
                  <div className="account-card__head">
                    <div>
                      <h2>修改密码</h2>
                      <p>修改成功后其他设备上的登录会失效，需要重新登录。</p>
                    </div>
                  </div>
                  <div className="account-password-grid">
                    <input type="password" value={oldPwd} onChange={(event) => setOldPwd(event.target.value)} placeholder="旧密码" autoComplete="current-password" />
                    <input type="password" value={newPwd} onChange={(event) => setNewPwd(event.target.value)} placeholder="新密码（至少 6 位）" autoComplete="new-password" />
                    <input type="password" value={confirmPwd} onChange={(event) => setConfirmPwd(event.target.value)} placeholder="确认新密码" autoComplete="new-password" />
                  </div>
                  <div className="account-card__actions">
                    <button
                      type="button"
                      className="account-primary"
                      disabled={!oldPwd || !newPwd || !confirmPwd}
                      onClick={savePassword}
                    >修改密码</button>
                  </div>
                </section>
              </div>
            )}

            {section === 'billing' && (
              <div className="account-stack">
                <section className="account-card">
                  <div className="account-card__head">
                    <div>
                      <h2>余额明细</h2>
                      <p>充值、消费与退款记录。当前版本暂未接入计费，所以暂时没有数据。</p>
                    </div>
                    <span className="account-chip account-chip--muted">暂未开通</span>
                  </div>
                  <div className="account-billing__summary">
                    <div className="account-billing__stat">
                      <span>当前余额</span>
                      <strong>¥ 0.00</strong>
                    </div>
                    <div className="account-billing__stat">
                      <span>累计充值</span>
                      <strong>¥ 0.00</strong>
                    </div>
                    <div className="account-billing__stat">
                      <span>累计消费</span>
                      <strong>¥ 0.00</strong>
                    </div>
                  </div>
                </section>

                <section className="account-card">
                  <div className="account-card__head">
                    <div>
                      <h2>变动记录</h2>
                      <p>按时间倒序展示每一笔余额变动。</p>
                    </div>
                  </div>
                  <div className="account-billing__table" role="table" aria-label="余额变动记录">
                    <div className="account-billing__row account-billing__row--head" role="row">
                      <span role="columnheader">时间</span>
                      <span role="columnheader">类型</span>
                      <span role="columnheader">金额</span>
                      <span role="columnheader">余额</span>
                      <span role="columnheader">说明</span>
                    </div>
                  </div>
                  <div className="account-billing__empty">
                    <span className="account-billing__empty-icon" aria-hidden="true">
                      <ReceiptText size={20} />
                    </span>
                    <strong>暂无余额变动</strong>
                    <span>余额功能上线后，充值、消费和退款会记录在这里。</span>
                  </div>
                </section>
              </div>
            )}

            {section === 'memory' && (
              <section className="account-card account-memory">
                <div className="account-card__head">
                  <div>
                    <h2>记忆管理</h2>
                    <p>Moon 只会记住你确认过、需要跨对话保留的项目事实。删除后不会再次注入。</p>
                  </div>
                  <span className="account-chip account-chip--muted">{memories.length} 条</span>
                </div>

                {memoryLoading ? (
                  <div className="account-empty">正在读取…</div>
                ) : memories.length === 0 ? (
                  <div className="account-empty">
                    还没有记忆。在对话里说“记住……”或让 Moon 记录项目约束即可。
                  </div>
                ) : (
                  <div className="account-memory__list">
                    {memories.map((memory) => (
                      <article className="account-memory__item" key={memory.id}>
                        <div className="account-memory__meta">
                          <span className="account-chip account-chip--primary">
                            {KIND_LABEL[memory.kind] || memory.kind}
                          </span>
                          <span className="account-memory__project">{projectName(memory.projectId)}</span>
                          {editingId !== memory.id && (
                            <button
                              type="button"
                              className="account-icon-btn"
                              aria-label="编辑记忆"
                              onClick={() => startEdit(memory)}
                            >
                              <Pencil size={14} />
                            </button>
                          )}
                          <button
                            type="button"
                            className="account-icon-btn account-icon-btn--danger"
                            aria-label="删除记忆"
                            onClick={() => deleteMemory(memory)}
                          >
                            <Trash2 size={14} />
                          </button>
                        </div>
                        {editingId === memory.id ? (
                          <div className="account-memory__editor">
                            <div className="account-memory__editor-row">
                              <select value={editKind} onChange={(event) => setEditKind(event.target.value)}>
                                {Object.entries(KIND_LABEL).map(([value, label]) => (
                                  <option key={value} value={value}>{label}</option>
                                ))}
                              </select>
                              <input
                                value={editConfidence}
                                onChange={(event) => setEditConfidence(event.target.value)}
                                placeholder="置信度 0-1"
                              />
                            </div>
                            <textarea
                              value={editContent}
                              onChange={(event) => setEditContent(event.target.value)}
                              rows={4}
                            />
                            <div className="account-card__actions">
                              <button type="button" className="account-ghost" onClick={() => setEditingId(null)}>取消</button>
                              <button type="button" className="account-primary" onClick={() => saveEdit(memory)}>保存</button>
                            </div>
                          </div>
                        ) : (
                          <>
                            <p className="account-memory__content">{memory.content}</p>
                            <div className="account-memory__foot">
                              置信度 {Math.round((memory.confidence ?? 0) * 100)}%
                              {memory.expiresAt ? ` · 到期 ${memory.expiresAt.slice(0, 10)}` : ''}
                            </div>
                          </>
                        )}
                      </article>
                    ))}
                  </div>
                )}
              </section>
            )}

            {section === 'settings' && (
              <div className="account-stack">
                <section className="account-card">
                  <div className="account-card__head">
                    <div>
                      <h2>全局权限</h2>
                      <p>新会话默认使用该模式；当前会话会同步切换，其他已有会话保持原设置。</p>
                    </div>
                    {settingsNotice && <span className="account-saved"><Check size={13} /> {settingsNotice}</span>}
                  </div>
                  <div className="account-choices">
                    {APPROVAL_OPTIONS.map((option) => {
                      const Icon = option.icon;
                      const active = settings.defaultApprovalMode === option.id;
                      return (
                        <button
                          key={option.id}
                          type="button"
                          role="radio"
                          aria-checked={active}
                          className={`account-choice${active ? ' is-active' : ''}${option.id === 'FULL_ACCESS' ? ' is-danger' : ''}`}
                          disabled={settingsSaving}
                          onClick={() => chooseApprovalMode(option.id)}
                        >
                          <span className="account-choice__icon" aria-hidden="true"><Icon size={17} /></span>
                          <span className="account-choice__body">
                            <span className="account-choice__label">{option.label}</span>
                            <span className="account-choice__desc">{option.description}</span>
                          </span>
                          <span className="account-choice__check" aria-hidden="true">
                            {active ? <Check size={15} /> : null}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </section>

                <section className="account-card">
                  <div className="account-card__head">
                    <div>
                      <h2>繁忙时的发送行为</h2>
                      <p>Moon 正在生成时，你按发送会发生什么。</p>
                    </div>
                  </div>
                  <div className="account-choices account-choices--two">
                    {BUSY_OPTIONS.map((option) => {
                      const active = settings.busySendBehavior === option.id;
                      return (
                        <button
                          key={option.id}
                          type="button"
                          role="radio"
                          aria-checked={active}
                          className={`account-choice${active ? ' is-active' : ''}`}
                          disabled={settingsSaving}
                          onClick={() => void applySetting({ busySendBehavior: option.id })}
                        >
                          <span className="account-choice__body">
                            <span className="account-choice__label">{option.label}</span>
                            <span className="account-choice__desc">{option.description}</span>
                          </span>
                          <span className="account-choice__check" aria-hidden="true">
                            {active ? <Check size={15} /> : null}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </section>

                <section className="account-card">
                  <div className="account-card__head">
                    <div>
                      <h2>工作步骤展示</h2>
                      <p>控制对话里思考、Agent 轨迹和工具调用的可见程度。</p>
                    </div>
                  </div>
                  <div className="account-choices">
                    {DISPLAY_OPTIONS.map((option) => {
                      const active = settings.workStepDisplay === option.id;
                      return (
                        <button
                          key={option.id}
                          type="button"
                          role="radio"
                          aria-checked={active}
                          className={`account-choice${active ? ' is-active' : ''}`}
                          disabled={settingsSaving}
                          onClick={() => void applySetting({ workStepDisplay: option.id })}
                        >
                          <span className="account-choice__body">
                            <span className="account-choice__label">{option.label}</span>
                            <span className="account-choice__desc">{option.description}</span>
                          </span>
                          <span className="account-choice__check" aria-hidden="true">
                            {active ? <Check size={15} /> : null}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </section>
              </div>
            )}

            {section === 'feedback' && (
              <section className="account-card account-feedback">
                <div className="account-card__head">
                  <div>
                    <h2>帮助与反馈</h2>
                    <p>遇到问题或有功能建议，截图并描述一下，我们会尽快处理。</p>
                  </div>
                  <MessageSquareHeart size={20} className="account-card__head-icon" aria-hidden="true" />
                </div>

                {feedbackSent ? (
                  <div className="account-feedback__sent">
                    <div className="account-feedback__sent-icon"><Check size={22} /></div>
                    <h3>反馈已提交</h3>
                    <p>感谢你的反馈，我们会尽快查看。</p>
                    <button type="button" className="account-ghost" onClick={() => setFeedbackSent(false)}>
                      再提交一条
                    </button>
                  </div>
                ) : (
                  <>
                    <label className="account-field">
                      <span>问题描述</span>
                      <textarea
                        rows={6}
                        maxLength={2000}
                        value={feedbackText}
                        onChange={(event) => setFeedbackText(event.target.value)}
                        placeholder="例如：生成视频时一直停在“准备中”，刷新后任务消失了。可以描述操作步骤、发生时间和期望结果。"
                      />
                      <span className="account-field__hint">{feedbackText.length}/2000</span>
                    </label>

                    <div className="account-feedback__images">
                      {feedbackImages.map((image, index) => (
                        <div className="account-feedback__thumb" key={image.url}>
                          <img src={assetUrl(image.url)} alt={image.name || `截图 ${index + 1}`} />
                          <button
                            type="button"
                            aria-label={`移除截图 ${index + 1}`}
                            onClick={() => setFeedbackImages((current) => current.filter((item) => item.url !== image.url))}
                          >
                            <X size={12} />
                          </button>
                        </div>
                      ))}
                      {feedbackImages.length < MAX_FEEDBACK_IMAGES && (
                        <label className={`account-feedback__add${feedbackUploading ? ' is-busy' : ''}`}>
                          <input
                            type="file"
                            accept="image/png,image/jpeg,image/webp,image/gif"
                            multiple
                            hidden
                            disabled={feedbackUploading}
                            onChange={(event) => {
                              void uploadFeedbackImages(event.target.files);
                              event.target.value = '';
                            }}
                          />
                          <ImagePlus size={18} />
                          <span>{feedbackUploading ? '上传中…' : '添加截图'}</span>
                        </label>
                      )}
                    </div>
                    <p className="account-field__hint">
                      最多 {MAX_FEEDBACK_IMAGES} 张，单张不超过 8MB。截图会随反馈一起保存。
                    </p>

                    <div className="account-card__actions">
                      <button
                        type="button"
                        className="account-primary"
                        disabled={feedbackSubmitting || feedbackText.trim().length < 5}
                        onClick={submitFeedback}
                      >
                        {feedbackSubmitting ? '提交中…' : '提交反馈'}
                      </button>
                    </div>
                  </>
                )}
              </section>
            )}

            {section === 'inbox' && (
              <section className="account-card account-inbox">
                <div className="account-card__head">
                  <div>
                    <h2>反馈收件箱</h2>
                    <p>用户提交的帮助与反馈。处理完可以标记为已处理，列表会保留记录。</p>
                  </div>
                  <span className="account-chip account-chip--muted">
                    {openInboxCount} 待处理 / {inbox.length} 总计
                  </span>
                </div>

                {inboxLoading ? (
                  <div className="account-empty">正在读取反馈…</div>
                ) : inbox.length === 0 ? (
                  <div className="account-empty">还没有用户反馈。</div>
                ) : (
                  <div className="account-inbox__list">
                    {inbox.map((item) => {
                      const resolved = item.status === 'resolved';
                      const who = item.userDisplayName?.trim() || item.userEmail || item.userId;
                      return (
                        <article
                          key={item.id}
                          className={`account-inbox__item${resolved ? ' is-resolved' : ''}`}
                        >
                          <header className="account-inbox__head">
                            <span className="account-inbox__avatar" aria-hidden="true">
                              {who.slice(0, 1).toUpperCase()}
                            </span>
                            <span className="account-inbox__who">
                              <strong>{item.userDisplayName?.trim() || '未命名用户'}</strong>
                              <span>{item.userEmail || item.userId}</span>
                            </span>
                            <span className={`account-chip ${resolved ? 'account-chip--muted' : 'account-chip--primary'}`}>
                              {resolved ? '已处理' : '待处理'}
                            </span>
                            <time className="account-inbox__time">{formatFeedbackTime(item.createdAt)}</time>
                          </header>

                          <p className="account-inbox__content">{item.content}</p>

                          {item.imageUrls.length > 0 && (
                            <div className="account-inbox__images">
                              {item.imageUrls.map((url, index) => (
                                <a
                                  key={`${item.id}-${url}`}
                                  href={assetUrl(url)}
                                  target="_blank"
                                  rel="noreferrer"
                                  title={`查看截图 ${index + 1}`}
                                >
                                  <img src={assetUrl(url)} alt={`反馈截图 ${index + 1}`} />
                                </a>
                              ))}
                            </div>
                          )}

                          <footer className="account-inbox__foot">
                            <button
                              type="button"
                              className={resolved ? 'account-ghost' : 'account-primary'}
                              disabled={inboxUpdatingId === item.id}
                              onClick={() => void setFeedbackStatus(item, resolved ? 'open' : 'resolved')}
                            >
                              {inboxUpdatingId === item.id
                                ? '更新中…'
                                : resolved ? '重新打开' : '标记已处理'}
                            </button>
                          </footer>
                        </article>
                      );
                    })}
                  </div>
                )}
              </section>
            )}
            </motion.div>
          </main>
        </div>
      </div>

      <FullAccessConfirmDialog
        open={confirmFullAccess}
        onCancel={() => setConfirmFullAccess(false)}
        onConfirm={() => {
          setConfirmFullAccess(false);
          void applySetting({ defaultApprovalMode: 'FULL_ACCESS' });
        }}
      />
    </div>
  );
}
