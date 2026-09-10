import { Image as ImageIcon, Paperclip } from 'lucide-react';

interface Props {
  onLocalImage: () => void;
  onMaterialPicker: () => void;
  onClose: () => void;
}

export function UploadMaterialMenu({
  onLocalImage,
  onMaterialPicker,
  onClose,
}: Props) {
  const choose = (action: () => void) => {
    onClose();
    action();
  };

  return (
    <div>
      <button
        type="button"
        onClick={() => choose(onMaterialPicker)}
        style={menuItem}
        onMouseEnter={(event) => { event.currentTarget.style.background = 'rgba(65,118,230,0.06)'; }}
        onMouseLeave={(event) => { event.currentTarget.style.background = 'transparent'; }}
      >
        <Paperclip size={16} strokeWidth={2} />
        <span>上传素材</span>
      </button>
      <button
        type="button"
        onClick={() => choose(onLocalImage)}
        style={menuItem}
        onMouseEnter={(event) => { event.currentTarget.style.background = 'rgba(65,118,230,0.06)'; }}
        onMouseLeave={(event) => { event.currentTarget.style.background = 'transparent'; }}
      >
        <ImageIcon size={16} strokeWidth={2} />
        <span>上传图片</span>
      </button>
    </div>
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
