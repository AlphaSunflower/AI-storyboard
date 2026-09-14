import { useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useProjectStore } from '../../stores/projectStore';
import { SceneCard } from '../scene/SceneCard';
import { LiveOrb } from '../ui/live-orb';
import { DS } from '../agent/ChatComposer';
import { CheckCircle2, Circle, FileText, MessageSquareText, Trash2, X } from 'lucide-react';
import {
  SCENE_DRAG_MIME,
  STORYBOARD_REFERENCE_MESSAGE,
  type EmbeddedSceneReference,
  type StoryboardReferenceMessage,
} from '../../lib/embeddedAppMessages';

interface MobileSceneListProps {
  onSelectScene: (id: string) => void;
  embedded?: boolean;
}

/** 手机端全宽分镜卡片列表 */
export function MobileSceneList({ onSelectScene, embedded = false }: MobileSceneListProps) {
  const { scenes, selectedSceneId, selectScene, addScene, deleteScene, currentProject } = useProjectStore();
  const listRef = useRef<HTMLDivElement>(null);
  const prevCountRef = useRef(0);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [notice, setNotice] = useState<string | null>(null);

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
    if (selectionMode) {
      setSelectedIds((current) =>
        current.includes(id) ? current.filter((sceneId) => sceneId !== id) : [...current, id]);
      return;
    }
    selectScene(id);
    onSelectScene(id);
  };

  const exitSelection = () => {
    setSelectionMode(false);
    setSelectedIds([]);
  };

  const deleteSelected = async () => {
    if (selectedIds.length === 0) return;
    if (!window.confirm(`确认删除已选中的 ${selectedIds.length} 个分镜？`)) return;
    for (const sceneId of selectedIds) {
      await deleteScene(sceneId);
    }
    exitSelection();
  };

  const buildReferences = (sceneIds: string[]): EmbeddedSceneReference[] => {
    const projectId = currentProject?.id;
    if (!projectId || sceneIds.length === 0) return [];
    return scenes
      .filter((scene) => sceneIds.includes(scene.id))
      .map((scene) => ({
        id: scene.id,
        projectId,
        sceneNumber: scene.sceneNumber,
      }));
  };

  const referenceSelected = () => {
    const projectId = currentProject?.id;
    const references = buildReferences(selectedIds);
    if (!projectId || references.length === 0) return;
    const message: StoryboardReferenceMessage = {
      type: STORYBOARD_REFERENCE_MESSAGE,
      projectId,
      scenes: references,
    };
    window.parent.postMessage(message, window.location.origin);
    setNotice(`已提交 ${references.length} 个分镜进入审核`);
    window.setTimeout(() => setNotice(null), 1800);
  };

  const startSceneDrag = (event: React.DragEvent, sceneId: string) => {
    if (!embedded) return;
    const dragIds = selectionMode && selectedIds.includes(sceneId) ? selectedIds : [sceneId];
    const references = buildReferences(dragIds);
    if (references.length === 0) return;
    const serialized = JSON.stringify(references);
    event.dataTransfer.effectAllowed = 'copy';
    event.dataTransfer.setData(SCENE_DRAG_MIME, serialized);
    event.dataTransfer.setData('text/plain', serialized);
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
      {selectionMode ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12, flexShrink: 0 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h2 style={{ font: 'var(--text-title-sm)', color: DS.ink, margin: 0 }}>
              已选 {selectedIds.length} 个
            </h2>
            <button
              type="button"
              onClick={exitSelection}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 9px',
                border: '1px solid var(--color-hairline)', borderRadius: 8,
                background: 'white', color: DS.textSecondary, fontSize: 12, cursor: 'pointer',
              }}
            >
              <X size={13} strokeWidth={2} /> 取消
            </button>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              disabled={selectedIds.length === 0}
              onClick={() => void deleteSelected()}
              style={{
                flex: 1, height: 34, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 5,
                border: '1px solid rgba(217,45,32,0.25)', borderRadius: 9,
                background: 'rgba(217,45,32,0.06)', color: '#d92d20', fontSize: 12, fontWeight: 600,
                cursor: selectedIds.length ? 'pointer' : 'not-allowed', opacity: selectedIds.length ? 1 : 0.45,
              }}
            >
              <Trash2 size={13} strokeWidth={1.9} /> 删除
            </button>
            <button
              type="button"
              disabled={selectedIds.length === 0}
              onClick={referenceSelected}
              style={{
                flex: 1.5, height: 34, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 5,
                border: 0, borderRadius: 9, background: DS.brand, color: 'white',
                fontSize: 12, fontWeight: 600, cursor: selectedIds.length ? 'pointer' : 'not-allowed',
                opacity: selectedIds.length ? 1 : 0.45,
              }}
            >
              <MessageSquareText size={13} strokeWidth={1.9} /> 进入审核
            </button>
          </div>
        </div>
      ) : (
        <div style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          marginBottom: 12, flexShrink: 0,
        }}>
          <h2 style={{ font: 'var(--text-title-sm)', color: DS.ink, margin: 0 }}>
            分镜列表
          </h2>
          <div style={{ display: 'flex', gap: 6 }}>
            {embedded && scenes.length > 0 && (
              <button
                type="button"
                onClick={() => setSelectionMode(true)}
                style={{
                  padding: '4px 11px', height: 28, fontSize: 12,
                  borderRadius: 'var(--rounded-md)',
                  border: '1px solid var(--color-hairline)',
                  background: 'white', cursor: 'pointer', color: DS.ink, fontWeight: 500,
                }}
              >多选</button>
            )}
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
        </div>
      )}

      {notice && (
        <div style={{
          marginBottom: 10, padding: '7px 10px', borderRadius: 8,
          background: 'rgba(65,118,230,0.08)', color: DS.brand, fontSize: 12,
        }}>{notice}</div>
      )}

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
          <div
            key={scene.id}
            draggable={embedded}
            onDragStart={(event) => startSceneDrag(event, scene.id)}
            style={{ position: 'relative', cursor: embedded ? 'grab' : undefined }}
          >
            <SceneCard
              scene={scene}
              isSelected={selectionMode ? selectedIds.includes(scene.id) : selectedSceneId === scene.id}
              onSelect={() => handleSelect(scene.id)}
            />
            {selectionMode && (
              <span style={{
                position: 'absolute', top: 10, left: 10, zIndex: 2,
                width: 24, height: 24, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                borderRadius: '50%', background: 'rgba(255,255,255,0.94)',
                color: selectedIds.includes(scene.id) ? DS.brand : DS.textCaption,
                boxShadow: '0 2px 8px rgba(15,17,21,0.14)', pointerEvents: 'none',
              }}>
                {selectedIds.includes(scene.id) ? <CheckCircle2 size={19} /> : <Circle size={18} />}
              </span>
            )}
          </div>
        ))
      )}
    </div>
  );
}
