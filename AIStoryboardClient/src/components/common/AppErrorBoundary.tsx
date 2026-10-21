import { Component, type ErrorInfo, type ReactNode } from 'react';
import { AlertTriangle, RotateCw } from 'lucide-react';

interface AppErrorBoundaryProps {
  children: ReactNode;
}

interface AppErrorBoundaryState {
  error: Error | null;
}

/**
 * 路由级错误边界：任何页面在渲染阶段抛错时，用一张可重试的兜底卡片替代整页白屏。
 *
 * 子窗（`?embedded=1` 的 iframe）里尤其重要——没有边界时 React 会卸载整棵树，
 * 用户只看到一块空白，连「出错了」都读不到。
 */
export class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  state: AppErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): AppErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[AppErrorBoundary] 页面渲染失败', error, info.componentStack);
  }

  private reload = () => {
    window.location.reload();
  };

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div
        role="alert"
        style={{
          minHeight: '100dvh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '24px 18px',
          background: 'var(--color-canvas)',
          color: 'var(--color-ink)',
        }}
      >
        <div
          style={{
            width: 'min(420px, 100%)',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 12,
            padding: '26px 22px',
            border: '1px solid var(--color-hairline)',
            borderRadius: 'var(--rounded-xl)',
            background: 'var(--color-panel)',
            boxShadow: 'var(--shadow-md)',
            textAlign: 'center',
          }}
        >
          <span
            aria-hidden="true"
            style={{
              width: 40,
              height: 40,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              borderRadius: '50%',
              background: 'var(--color-primary-tint)',
              color: 'var(--color-primary-active)',
            }}
          >
            <AlertTriangle size={20} strokeWidth={1.9} />
          </span>
          <h1 style={{ margin: 0, fontSize: 16, fontWeight: 650 }}>页面加载出错了</h1>
          <p style={{ margin: 0, fontSize: 13, lineHeight: 1.6, color: 'var(--color-muted)' }}>
            当前页面渲染时遇到异常，已阻止整页空白。重新加载通常可以恢复；未保存的编辑会丢失。
          </p>
          <code
            style={{
              maxWidth: '100%',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              fontSize: 11.5,
              color: 'var(--color-muted)',
            }}
            title={error.message}
          >
            {error.name}: {error.message}
          </code>
          <button
            type="button"
            onClick={this.reload}
            style={{
              marginTop: 2,
              height: 36,
              padding: '0 18px',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 7,
              border: 0,
              borderRadius: 'var(--rounded-md)',
              background: 'var(--color-primary)',
              color: 'var(--color-on-primary)',
              fontSize: 13,
              fontWeight: 600,
              cursor: 'pointer',
            }}
          >
            <RotateCw size={14} strokeWidth={2} /> 重新加载
          </button>
        </div>
      </div>
    );
  }
}
