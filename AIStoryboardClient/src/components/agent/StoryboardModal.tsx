import { useProjectStore } from '../../stores/projectStore';

export function StoryboardContent() {
  const currentProject = useProjectStore((s) => s.currentProject);

  return (
    <div className="flex flex-col items-center gap-4 py-6">
      <p className="text-sm" style={{ color: 'var(--color-muted)' }}>
        查看当前项目的分镜导出情况
      </p>
      <button
        disabled={!currentProject}
        onClick={() => window.open(`/editor?projectId=${currentProject?.id}`, '_blank')}
        className="px-5 py-2 rounded-lg text-sm font-medium transition-opacity disabled:opacity-40 disabled:cursor-not-allowed"
        style={{ background: 'var(--color-primary)', color: '#fff' }}
      >
        打开分镜页面
      </button>
    </div>
  );
}
