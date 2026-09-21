import { createPortal } from 'react-dom';
import { Clapperboard, FolderOpen, Library, Package, X } from 'lucide-react';

interface Props {
  open: boolean;
  onClose: () => void;
  onAssetGroup: () => void;
  onProjectLibrary: () => void;
  onProjectOutput: () => void;
  onScene: () => void;
}

export function UploadMaterialPickerModal({
  open,
  onClose,
  onAssetGroup,
  onProjectLibrary,
  onProjectOutput,
  onScene,
}: Props) {
  if (!open) return null;

  const choose = (action: () => void) => {
    onClose();
    action();
  };

  return createPortal(
    <div
      style={overlay}
      onPointerDown={(event) => { if (event.target === event.currentTarget) onClose(); }}
    >
      <div style={dialog} onPointerDown={(event) => event.stopPropagation()}>
        <header style={header}>
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, color: 'rgb(15,17,21)' }}>上传素材</div>
            <div style={{ fontSize: 12, color: 'rgb(152,158,168)', marginTop: 3 }}>选择要添加到本次对话的素材来源</div>
          </div>
          <button type="button" onClick={onClose} style={iconButton} aria-label="关闭">
            <X size={17} />
          </button>
        </header>
        <div style={body}>
          <Option
            icon={<Package size={18} />}
            title="关联资产组"
            description="从项目库选择人物、道具、场景等资产组"
            onClick={() => choose(onAssetGroup)}
          />
          <Option
            icon={<Library size={18} />}
            title="项目库素材"
            description="选择项目库文件或文件夹，也可直接上传入库"
            onClick={() => choose(onProjectLibrary)}
          />
          <Option
            icon={<FolderOpen size={18} />}
            title="上传项目产出素材"
            description="从项目已生成的图片中选择参考素材"
            onClick={() => choose(onProjectOutput)}
          />
          <Option
            icon={<Clapperboard size={18} />}
            title="上传分镜"
            description="从当前项目选择分镜并直接进入审核"
            onClick={() => choose(onScene)}
          />
        </div>
      </div>
    </div>,
    document.body,
  );
}

function Option({ icon, title, description, onClick }: {
  icon: React.ReactNode;
  title: string;
  description: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={option}
      onMouseEnter={(event) => { event.currentTarget.style.background = 'rgba(65,118,230,0.06)'; event.currentTarget.style.borderColor = 'rgba(65,118,230,0.32)'; }}
      onMouseLeave={(event) => { event.currentTarget.style.background = 'white'; event.currentTarget.style.borderColor = 'rgba(0,0,0,0.08)'; }}
    >
      <span style={optionIcon}>{icon}</span>
      <span style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
        <span style={{ display: 'block', fontSize: 14, fontWeight: 600, color: 'rgb(15,17,21)' }}>{title}</span>
        <span style={{ display: 'block', marginTop: 3, fontSize: 12, color: 'rgb(152,158,168)' }}>{description}</span>
      </span>
    </button>
  );
}

const overlay: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  zIndex: 1350,
  padding: 16,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  background: 'rgba(15,17,21,0.42)',
};
const dialog: React.CSSProperties = {
  width: '100%',
  maxWidth: 460,
  overflow: 'hidden',
  borderRadius: 16,
  background: 'white',
  boxShadow: '0 18px 48px rgba(15,17,21,0.24)',
};
const header: React.CSSProperties = {
  padding: '14px 18px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  borderBottom: '1px solid rgba(0,0,0,0.08)',
};
const body: React.CSSProperties = {
  padding: 14,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};
const option: React.CSSProperties = {
  width: '100%',
  padding: '12px 14px',
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 12,
  background: 'white',
  cursor: 'pointer',
  transition: 'background 0.15s, border-color 0.15s',
};
const optionIcon: React.CSSProperties = {
  width: 36,
  height: 36,
  borderRadius: 10,
  display: 'grid',
  placeItems: 'center',
  color: 'rgb(65,118,230)',
  background: 'rgba(65,118,230,0.08)',
  flexShrink: 0,
};
const iconButton: React.CSSProperties = {
  padding: 4,
  border: 'none',
  background: 'transparent',
  color: 'rgb(152,158,168)',
  cursor: 'pointer',
};
