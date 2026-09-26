import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Images, List, Sparkles } from 'lucide-react';
import { useAgentStore } from '../../stores/agentStore';
import { useChatAppOpener } from './apps/ChatAppsContext';
import './StoryboardResultActions.css';

/**
 * 分镜写入项目后的固定下一步操作。
 * 新用户从这里可以直接进入分镜列表，不需要知道编辑器路径或聊天指令。
 */
export function StoryboardResultActions() {
  const navigate = useNavigate();
  const openChatApp = useChatAppOpener();
  const sendMessage = useAgentStore((state) => state.sendMessage);
  const [pending, setPending] = useState<'inspect' | 'images' | null>(null);

  const askAgent = async (kind: 'inspect' | 'images', content: string) => {
    if (pending) return;
    setPending(kind);
    try {
      await sendMessage(content, { routingHint: 'intent-storyboard' });
    } finally {
      setPending(null);
    }
  };

  return (
    <section className="storyboard-result-actions" aria-label="分镜后续操作">
      <span className="storyboard-result-actions__label">接下来可以</span>
      <div className="storyboard-result-actions__items">
        <button
          type="button"
          className="is-primary"
          onClick={() => openChatApp ? openChatApp('editor') : navigate('/editor')}
        >
          <List size={14} />
          查看分镜列表
        </button>
        <button
          type="button"
          disabled={pending !== null}
          onClick={() => void askAgent('inspect', '请检查当前项目所有分镜的缺图和缺视频情况')}
        >
          <Sparkles size={14} />
          {pending === 'inspect' ? '检查中…' : '检查缺图/缺视频'}
        </button>
        <button
          type="button"
          disabled={pending !== null}
          onClick={() => void askAgent('images', '把当前项目所有缺图的分镜图片生成出来')}
        >
          <Images size={14} />
          {pending === 'images' ? '提交中…' : '生成全部缺图'}
        </button>
      </div>
    </section>
  );
}
