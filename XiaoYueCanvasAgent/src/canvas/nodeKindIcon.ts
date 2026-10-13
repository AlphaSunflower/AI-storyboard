import {
  Clapperboard, FileText, Image as ImageIcon, LayoutGrid, Layers, Merge, MessageSquare,
  Sparkles, Type, Video, Wand2,
} from 'lucide-react';

/**
 * 节点 kind → 图标。节点库菜单与外置标题共用同一份映射，
 * 避免「同一个节点在两处显示不同图标」。
 */
const KIND_ICONS: Record<string, typeof Type> = {
  text: Type,
  script: FileText,
  note: MessageSquare,
  prompt: Wand2,
  sequence: Layers,
  storyboard: Clapperboard,
  storyboardGen: LayoutGrid,
  storyboardPlanner: LayoutGrid,
  image: ImageIcon,
  imageGen: Sparkles,
  textToImage: Sparkles,
  imageToImage: Sparkles,
  batchImageRefine: Sparkles,
  imageGeneration: Sparkles,
  video: Video,
  videoGen: Video,
  textToVideo: Video,
  imageToVideo: Video,
  firstLastFrameToVideo: Video,
  referenceToVideo: Video,
  videoGeneration: Video,
  videoMerge: Merge,
};

export function nodeKindIcon(kind: string | undefined) {
  return (kind ? KIND_ICONS[kind] : undefined) ?? ImageIcon;
}
