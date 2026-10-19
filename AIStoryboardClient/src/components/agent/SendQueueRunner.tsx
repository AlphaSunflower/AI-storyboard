import { useEffect, useRef } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { useSendQueueStore } from '../../stores/sendQueueStore';

/**
 * 全局发送队列执行器：只挂一次，避免桌面/移动两套输入卡同时存在时重复出队。
 *
 * 只处理「当前会话」的队列：队列里的消息是该会话的下一轮，切到别的会话时不会误发。
 * 当前回合完全收尾后取队首，作为普通 turn 发送；发送后 streaming 变真，等待下一轮结束。
 */
export function SendQueueRunner() {
  const activeConversationId = useAgentStore((state) => state.activeConversationId);
  const streaming = useAgentStore((state) => state.streaming);
  const waitingHumanInput = useAgentStore((state) => state.waitingHumanInput);
  const waitingVideoPlan = useAgentStore((state) => state.waitingVideoPlan);
  const agentVideoTask = useAgentStore((state) => state.agentVideoTask);
  const canvasBinding = useAgentStore((state) => state.canvasBinding);
  const canvasExitPending = useAgentStore((state) => state.canvasExitPending);
  const sendMessage = useAgentStore((state) => state.sendMessage);
  const queuedLength = useSendQueueStore((state) => (
    activeConversationId ? state.queues[activeConversationId]?.length ?? 0 : 0
  ));
  const shift = useSendQueueStore((state) => state.shift);
  const lockRef = useRef(false);

  const busy = (streaming && !agentVideoTask) || !!waitingHumanInput || !!waitingVideoPlan
    || !!canvasBinding || canvasExitPending;

  useEffect(() => {
    if (busy) {
      lockRef.current = false;
      return;
    }
    if (!activeConversationId || queuedLength === 0 || lockRef.current) return;
    lockRef.current = true;
    const next = shift(activeConversationId);
    if (!next) {
      lockRef.current = false;
      return;
    }
    sendMessage(next.content, next.opts);
    // sendMessage 会同步把 streaming 置真；下一帧再解锁，避免同一轮里连发。
    window.setTimeout(() => { lockRef.current = false; }, 0);
  }, [busy, activeConversationId, queuedLength, sendMessage, shift]);

  return null;
}
