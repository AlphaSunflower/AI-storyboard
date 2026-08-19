export function StoryboardModal() {
  return (
    <div className="text-sm" style={{ color: 'var(--color-body)' }}>
      <p className="mb-4">分镜编辑器在独立页面中运行。</p>
      <a
        href="/storyboard"
        target="_blank"
        rel="noopener noreferrer"
        className="inline-block px-4 py-2 rounded-md text-sm font-medium"
        style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)' }}
      >
        打开分镜编辑器 →
      </a>
    </div>
  );
}
