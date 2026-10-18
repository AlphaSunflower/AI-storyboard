import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Brain, LogOut, Pencil, Trash2 } from 'lucide-react';
import { userApi, type UserStats } from '../api/user';
import { memoryApi, type AgentMemory } from '../api/memory';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { notify } from '../stores/toastStore';

const KIND_LABEL: Record<string, string> = {
  goal: '目标',
  style: '风格',
  constraint: '约束',
  decision: '决策',
  feedback: '反馈',
};

const pageStyle: React.CSSProperties = {
  minHeight: '100svh',
  background: 'var(--color-canvas)',
  color: 'var(--color-ink)',
  padding: '32px 20px 64px',
};
const cardStyle: React.CSSProperties = {
  background: 'white',
  border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-lg)',
  padding: 22,
};
const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '9px 12px',
  border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)',
  font: 'var(--text-body-sm)',
  color: 'var(--color-ink)',
  outline: 'none',
  boxSizing: 'border-box',
  background: 'white',
};
const primaryBtn: React.CSSProperties = {
  padding: '8px 16px',
  border: 'none',
  borderRadius: 'var(--rounded-md)',
  background: 'var(--color-primary)',
  color: 'white',
  font: 'var(--text-caption)',
  cursor: 'pointer',
};

function errorMessage(error: unknown): string {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message || '操作失败';
}

export function AccountPage() {
  const navigate = useNavigate();
  const logout = useAuthStore((state) => state.logout);
  const [checking, setChecking] = useState(true);
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
  const projects = useProjectStore((state) => state.projects);
  const loadProjects = useProjectStore((state) => state.loadProjects);

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
    }).catch(() => undefined);
    void userApi.getStats().then((response) => setStats(response.data.data)).catch(() => undefined);
    void loadProjects();
    void loadMemories();
  }, [checking, loadProjects]);

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

  if (checking) {
    return <div style={{ ...pageStyle, display: 'grid', placeItems: 'center' }}>正在加载…</div>;
  }

  return (
    <div style={pageStyle}>
      <div style={{ maxWidth: 980, margin: '0 auto' }}>
        <header style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
          <button
            type="button"
            onClick={() => navigate('/chat')}
            style={{ ...primaryBtn, background: 'transparent', color: 'var(--color-ink)', border: '1px solid var(--color-hairline)', display: 'inline-flex', alignItems: 'center', gap: 6 }}
          >
            <ArrowLeft size={15} /> 返回 Moon
          </button>
          <h1 style={{ margin: 0, font: 'var(--text-title-lg)', flex: 1 }}>个人信息</h1>
          <button
            type="button"
            onClick={logout}
            style={{ ...primaryBtn, background: 'transparent', color: 'var(--color-error)', border: '1px solid var(--color-hairline)', display: 'inline-flex', alignItems: 'center', gap: 6 }}
          >
            <LogOut size={15} /> 退出登录
          </button>
        </header>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 18 }} className="account-grid">
          <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            <section style={cardStyle}>
              <h2 style={{ margin: '0 0 14px', fontSize: 15 }}>账号</h2>
              <div style={{ display: 'flex', gap: 10, marginBottom: 16 }}>
                {[
                  { value: stats?.imageCount, label: '生成图片' },
                  { value: stats?.videoCount, label: '生成视频' },
                  { value: stats?.projectCount, label: '项目总数' },
                ].map((item) => (
                  <div key={item.label} style={{ flex: 1, padding: '12px 8px', background: 'var(--color-surface-card)', borderRadius: 'var(--rounded-md)', textAlign: 'center' }}>
                    <div style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-primary)' }}>{item.value ?? 0}</div>
                    <div style={{ fontSize: 11, color: 'var(--color-muted)', marginTop: 4 }}>{item.label}</div>
                  </div>
                ))}
              </div>
              <label style={{ fontSize: 11, color: 'var(--color-muted)' }}>名称</label>
              <input style={{ ...inputStyle, margin: '4px 0 10px' }} value={name} onChange={(event) => setName(event.target.value)} />
              <label style={{ fontSize: 11, color: 'var(--color-muted)' }}>邮箱</label>
              <input style={{ ...inputStyle, margin: '4px 0 12px' }} value={email} onChange={(event) => setEmail(event.target.value)} />
              <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                <button type="button" style={primaryBtn} onClick={saveProfile}>保存基本信息</button>
              </div>
            </section>

            <section style={cardStyle}>
              <h2 style={{ margin: '0 0 12px', fontSize: 15 }}>修改密码</h2>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
                <input style={inputStyle} type="password" value={oldPwd} onChange={(event) => setOldPwd(event.target.value)} placeholder="旧密码" />
                <input style={inputStyle} type="password" value={newPwd} onChange={(event) => setNewPwd(event.target.value)} placeholder="新密码（至少 6 位）" />
                <input style={inputStyle} type="password" value={confirmPwd} onChange={(event) => setConfirmPwd(event.target.value)} placeholder="确认新密码" />
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button type="button" style={primaryBtn} disabled={!oldPwd || !newPwd || !confirmPwd} onClick={savePassword}>修改密码</button>
                </div>
              </div>
            </section>
          </div>

          <section style={{ ...cardStyle, display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Brain size={17} color="var(--color-primary)" />
              <h2 style={{ margin: 0, fontSize: 15, flex: 1 }}>记忆管理</h2>
              <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>{memories.length} 条</span>
            </div>
            <p style={{ margin: 0, fontSize: 12, color: 'var(--color-muted)', lineHeight: 1.6 }}>
              Moon 只会记住你确认过、需要跨对话保留的项目事实。删除后不会再次注入。
            </p>
            {memoryLoading ? (
              <div style={{ padding: 24, textAlign: 'center', color: 'var(--color-muted)' }}>正在读取…</div>
            ) : memories.length === 0 ? (
              <div style={{ padding: 28, textAlign: 'center', color: 'var(--color-muted)', fontSize: 13 }}>
                还没有记忆。在对话里说“记住……”或让 Moon 记录项目约束即可。
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {memories.map((memory) => (
                  <article key={memory.id} style={{ border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)', padding: '11px 12px', background: 'var(--color-canvas)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                      <span style={{ fontSize: 11, color: 'var(--color-primary)', border: '1px solid rgba(204,120,92,0.35)', borderRadius: 999, padding: '1px 8px' }}>
                        {KIND_LABEL[memory.kind] || memory.kind}
                      </span>
                      <span style={{ fontSize: 11, color: 'var(--color-muted)', flex: 1 }}>{projectName(memory.projectId)}</span>
                      {editingId !== memory.id && (
                        <button
                          type="button"
                          aria-label="编辑记忆"
                          onClick={() => startEdit(memory)}
                          style={{ border: 'none', background: 'transparent', color: 'var(--color-muted)', cursor: 'pointer', padding: 2 }}
                        >
                          <Pencil size={14} />
                        </button>
                      )}
                      <button
                        type="button"
                        aria-label="删除记忆"
                        onClick={() => deleteMemory(memory)}
                        style={{ border: 'none', background: 'transparent', color: 'var(--color-muted)', cursor: 'pointer', padding: 2 }}
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                    {editingId === memory.id ? (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 8 }}>
                          <select value={editKind} onChange={(event) => setEditKind(event.target.value)} style={{ ...inputStyle, flex: 1 }}>
                            {Object.entries(KIND_LABEL).map(([value, label]) => (
                              <option key={value} value={value}>{label}</option>
                            ))}
                          </select>
                          <input
                            style={{ ...inputStyle, width: 110 }}
                            value={editConfidence}
                            onChange={(event) => setEditConfidence(event.target.value)}
                            placeholder="置信度 0-1"
                          />
                        </div>
                        <textarea
                          value={editContent}
                          onChange={(event) => setEditContent(event.target.value)}
                          rows={4}
                          style={{ ...inputStyle, resize: 'vertical', lineHeight: 1.6 }}
                        />
                        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                          <button type="button" style={{ ...primaryBtn, background: 'transparent', color: 'var(--color-muted)', border: '1px solid var(--color-hairline)' }} onClick={() => setEditingId(null)}>
                            取消
                          </button>
                          <button type="button" style={primaryBtn} onClick={() => saveEdit(memory)}>保存</button>
                        </div>
                      </div>
                    ) : (
                      <>
                        <div style={{ fontSize: 13, lineHeight: 1.65, whiteSpace: 'pre-wrap' }}>{memory.content}</div>
                        <div style={{ marginTop: 6, fontSize: 11, color: 'var(--color-muted)' }}>
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
        </div>
      </div>
    </div>
  );
}
