import { useProjectStore } from '../../stores/projectStore';
import { SceneCard } from '../scene/SceneCard';

const middlePanelWidth = 380;

export function SceneListPanel() {
  const { scenes, selectedSceneId, selectScene, addScene, currentProject } =
    useProjectStore();

  return (
    <div
      style={{
        width: middlePanelWidth,
        minWidth: middlePanelWidth,
        borderRight: '1px solid var(--color-hairline)',
        background: 'white',
        padding: 'var(--space-md)',
        overflowY: 'auto',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* Header row */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 12,
        }}
      >
        <h2
          style={{
            font: 'var(--text-title-sm)',
            color: 'var(--color-ink)',
            margin: 0,
          }}
        >
          分镜列表
        </h2>
        {currentProject && (
          <button
            onClick={() => addScene(currentProject.id)}
            style={{
              padding: '4px 14px',
              height: 28,
              fontSize: 12,
              borderRadius: 'var(--rounded-md)',
              border: '1px solid var(--color-hairline)',
              background: 'white',
              cursor: 'pointer',
              color: 'var(--color-body)',
              fontWeight: 500,
            }}
          >
            + 添加
          </button>
        )}
      </div>

      {/* Empty state */}
      {scenes.length === 0 ? (
        <div
          style={{
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <p
            style={{
              color: 'var(--color-muted-soft)',
              fontSize: 13,
              textAlign: 'center',
              padding: 'var(--space-lg)',
            }}
          >
            暂无分镜
            <br />
            请输入剧本并点击"生成分镜脚本"
          </p>
        </div>
      ) : (
        /* Scene cards */
        scenes.map((scene) => (
          <SceneCard
            key={scene.id}
            scene={scene}
            isSelected={selectedSceneId === scene.id}
            onSelect={() => selectScene(scene.id)}
          />
        ))
      )}
    </div>
  );
}
