import { Clapperboard, FileUp, Library } from 'lucide-react';

interface Props {
  onLocalFile: () => void;
  onProjectLibrary: () => void;
  onScene: () => void;
  onClose: () => void;
}

/** 「+」按钮的下拉列表：直接列出三类来源，不再经过「上传素材」弹窗。 */
export function UploadMaterialMenu({
  onLocalFile,
  onProjectLibrary,
  onScene,
  onClose,
}: Props) {
  const choose = (action: () => void) => {
    onClose();
    action();
  };

  return (
    <div>
      <MenuItem icon={<FileUp size={16} strokeWidth={2} />} label="上传文件" index={0} onClick={() => choose(onLocalFile)} />
      <MenuItem
        icon={<Library size={16} strokeWidth={2} />}
        label="上传项目库素材"
        index={1}
        onClick={() => choose(onProjectLibrary)}
      />
      <MenuItem
        icon={<Clapperboard size={16} strokeWidth={2} />}
        label="上传分镜"
        index={2}
        onClick={() => choose(onScene)}
      />
    </div>
  );
}

function MenuItem({ icon, label, index, onClick }: {
  icon: React.ReactNode;
  label: string;
  index: number;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className="chat-plus-menu__item"
      onClick={onClick}
      style={{ ...menuItem, '--item-index': index } as React.CSSProperties}
      onMouseEnter={(event) => { event.currentTarget.style.background = 'rgba(65,118,230,0.06)'; }}
      onMouseLeave={(event) => { event.currentTarget.style.background = 'transparent'; }}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

const menuItem: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  width: '100%',
  minWidth: 0,
  padding: '8px 12px',
  border: 'none',
  borderRadius: 8,
  background: 'transparent',
  color: 'rgb(15,17,21)',
  fontSize: 14,
  textAlign: 'left',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
};
