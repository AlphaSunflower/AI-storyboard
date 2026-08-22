import { useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useProjectStore } from '../../stores/projectStore';
import { SceneCard } from '../scene/SceneCard';
import { LiveOrb } from '../ui/live-orb';
import { DS } from '../agent/ChatComposer';
import { FileText } from 'lucide-react';

interface MobileSceneListProps {
  onSelectScene: (id: string) => void;
}

/** 手机端全宽分镜卡片列表 */
export function MobileSceneList({ onSelectScene }: MobileSceneListProps) {
  const { scenes, selectedSceneId, selectScene, addScene, currentProject } = useProjectStore();
  const listRef = useRef<HTMLDivElement>(null);
  const prevCountRef = useRef(0);

  // 新卡片入场动画
  useGSAP(() => {
    const prev = prevCountRef.current;
    const current = scenes.length;
    prevCountRef.current = current;
    if (current <= prev) return;
    const container = listRef.current;
    if (!container) return;
    const cards = Array.from(container.children).slice(-(current - prev));
    if (cards.length === 0) return;
    gsap.from(cards, { y: 18, opacity: 0, duration: 0.3, ease: 'power2.out', stagger: 0.05 });
  }, { dependencies: [scenes.length], scope: listRef });

  const handleSelect = (id: string) => {
    selectScene(id);
    onSelectScene(id);
  };

  return (
    <div
      ref={listRef}
      style={{
        height: '100%', overflowY: 'auto', background: 'white',
        padding: '12px', display: 'flex', flexDirection: 'column',
      }}
    >
      {/* Header */}
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        marginBottom: 12, flexShrink: 0,
      }}>
        <h2 style={{ font: 'var(--text-title-sm)', color: DS.ink, margin: 0 }}>
          分镜列表
        </h2>
        {currentProject && (
          <button
            onClick={() => addScene(currentProject.id)}
            style={{
              padding: '4px 14px', height: 28, fontSize: 12,
              borderRadius: 'var(--rounded-md)',
              border: '1px solid var(--color-hairline)',
              background: 'white', cursor: 'pointer', color: DS.ink, fontWeight: 500,
            }}
          >+ 添加</button>
        )}
      </div>

      {/* Empty */}
      {scenes.length === 0 ? (
        <div style={{
          flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
            <LiveOrb variant="webgl" colors={["#A33A18", "#D4682A", "#E8B45A"]} size={70} />
            <p style={{ color: DS.textCaption, fontSize: 13, textAlign: 'center', margin: 0 }}>
              暂无分镜<br />点击右上角 <FileText size={12} strokeWidth={1.8} /> 输入剧本生成
            </p>
          </div>
        </div>
      ) : (
        scenes.map((scene) => (
          <SceneCard
            key={scene.id}
            scene={scene}
            isSelected={selectedSceneId === scene.id}
            onSelect={() => handleSelect(scene.id)}
          />
        ))
      )}
    </div>
  );
}
