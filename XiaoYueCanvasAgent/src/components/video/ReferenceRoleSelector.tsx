import { memo, useState } from 'react';

/**
 * 参考图用途枚举（与后端 ReferenceImageRole 对齐）
 */
export type ReferenceImageRole = 'FIRST_FRAME' | 'LAST_FRAME' | 'REFERENCE';

/**
 * 参考图用途选择结果
 */
export interface ReferenceRoleSelection {
  /** 单图或所有图的统一 role */
  role?: ReferenceImageRole;
  /** 双图首尾帧模式：第一张为首帧，第二张为尾帧 */
  firstLastFrame?: boolean;
}

interface ReferenceRoleSelectorProps {
  /** 参考图数量 */
  imageCount: number;
  /** 参考图预览 URL 列表（可选，用于展示） */
  imageUrls?: string[];
  /** 用户确认选择 */
  onSelect: (selection: ReferenceRoleSelection) => void;
  /** 用户取消 */
  onCancel: () => void;
}

/**
 * 参考图用途选择器组件。
 *
 * 根据图片数量显示不同的选项：
 * - 1 张：首帧 / 尾帧 / 参考图
 * - 2 张：首尾帧 / 参考图
 * - ≥3 张：不显示（默认多模态参考）
 */
export const ReferenceRoleSelector = memo(function ReferenceRoleSelector({
  imageCount,
  imageUrls,
  onSelect,
  onCancel,
}: ReferenceRoleSelectorProps) {
  const [hoveredOption, setHoveredOption] = useState<string | null>(null);

  // ≥3 张默认多模态参考，不弹出选择器
  if (imageCount >= 3) {
    return null;
  }

  const handleSelect = (type: string) => {
    switch (type) {
      case 'first_frame':
        onSelect({ role: 'FIRST_FRAME' });
        break;
      case 'last_frame':
        onSelect({ role: 'LAST_FRAME' });
        break;
      case 'reference':
        onSelect({ role: 'REFERENCE' });
        break;
      case 'first_last_frame':
        onSelect({ firstLastFrame: true });
        break;
    }
  };

  return (
    <div className="reference-role-selector">
      <div className="reference-role-selector__header">
        <span className="reference-role-selector__icon">🎬</span>
        <h4 className="reference-role-selector__title">
          {imageCount === 1 ? '请选择这张图片的用途：' : '请选择这两张图片的用途：'}
        </h4>
      </div>

      {/* 图片预览 */}
      {imageUrls && imageUrls.length > 0 && (
        <div className="reference-role-selector__previews">
          {imageUrls.slice(0, 2).map((url, index) => (
            <div key={index} className="reference-role-selector__preview">
              <img src={url} alt={`参考图 ${index + 1}`} />
              <span className="reference-role-selector__preview-label">
                {imageCount === 2 && index === 0 ? '图1' : imageCount === 2 ? '图2' : ''}
              </span>
            </div>
          ))}
        </div>
      )}

      {/* 选项按钮 */}
      <div className="reference-role-selector__options">
        {imageCount === 1 ? (
          <>
            <RoleOption
              id="first_frame"
              icon="🖼️"
              title="首帧"
              description="锁定视频起始画面"
              isHovered={hoveredOption === 'first_frame'}
              onHover={setHoveredOption}
              onClick={() => handleSelect('first_frame')}
            />
            <RoleOption
              id="last_frame"
              icon="🎯"
              title="尾帧"
              description="锁定视频结束画面"
              isHovered={hoveredOption === 'last_frame'}
              onHover={setHoveredOption}
              onClick={() => handleSelect('last_frame')}
            />
            <RoleOption
              id="reference"
              icon="🎨"
              title="参考图"
              description="模型自由演绎"
              isHovered={hoveredOption === 'reference'}
              onHover={setHoveredOption}
              onClick={() => handleSelect('reference')}
            />
          </>
        ) : (
          <>
            <RoleOption
              id="first_last_frame"
              icon="🎬"
              title="首尾帧"
              description="图1为首帧，图2为尾帧"
              isHovered={hoveredOption === 'first_last_frame'}
              onHover={setHoveredOption}
              onClick={() => handleSelect('first_last_frame')}
            />
            <RoleOption
              id="reference"
              icon="🎨"
              title="参考图"
              description="两张都作为参考图"
              isHovered={hoveredOption === 'reference'}
              onHover={setHoveredOption}
              onClick={() => handleSelect('reference')}
            />
          </>
        )}
      </div>

      {/* 取消按钮 */}
      <button
        className="reference-role-selector__cancel"
        onClick={onCancel}
      >
        取消
      </button>
    </div>
  );
});

/** 单个选项按钮 */
interface RoleOptionProps {
  id: string;
  icon: string;
  title: string;
  description: string;
  isHovered: boolean;
  onHover: (id: string | null) => void;
  onClick: () => void;
}

const RoleOption = memo(function RoleOption({
  id,
  icon,
  title,
  description,
  isHovered,
  onHover,
  onClick,
}: RoleOptionProps) {
  return (
    <button
      className={`reference-role-selector__option ${isHovered ? 'is-hovered' : ''}`}
      onMouseEnter={() => onHover(id)}
      onMouseLeave={() => onHover(null)}
      onClick={onClick}
    >
      <span className="reference-role-selector__option-icon">{icon}</span>
      <div className="reference-role-selector__option-content">
        <span className="reference-role-selector__option-title">{title}</span>
        <span className="reference-role-selector__option-desc">{description}</span>
      </div>
    </button>
  );
});
