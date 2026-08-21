import { useRef, useEffect } from 'react';
import { useMicVolume } from '../../hooks/useMicVolume';
import { useIsMobile } from '../../hooks/useIsMobile';

const DS_BLUE = 'rgb(65, 118, 230)';

interface MicButtonProps {
  /** 录音中回调——用于父组件禁用发送等 */
  onToggle?: (active: boolean) => void;
  /** 录音结束回调（WAV Blob，16kHz 单声道）——接语音转文字 */
  onRecorded?: (wav: Blob) => void;
  /** 外部禁用（识别中/流式回复中等场景） */
  disabled?: boolean;
  /** 暴露麦克风 API（录音中取快照 WAV）——流式识别用 */
  onApiReady?: (api: { getWavSnapshot: () => Promise<Blob | null> }) => void;
  /** 实时 PCM 回调（16kHz mono Int16，录音中每块触发）——流式识别推流用 */
  onPcm?: (pcm16k: Int16Array) => void;
}

/**
 * 麦克风按钮：点击录音，canvas 径向波形随音量变化。
 * 不支持 getUserMedia 时返回 null。
 */
export function MicButton({ onToggle, onRecorded, disabled, onApiReady, onPcm }: MicButtonProps) {
  const isMobile = useIsMobile();
  const { volume, freqData, isActive, isSupported, toggle, stopAndGetWav, getWavSnapshot } = useMicVolume(64, onPcm);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rafRef = useRef(0);

  const size = isMobile ? 52 : 42;
  const canvasSize = size + 28; // 波形向外扩展 14px

  // 通知父组件录音状态
  useEffect(() => { onToggle?.(isActive); }, [isActive, onToggle]);

  // 暴露麦克风 API（快照引用稳定，父组件录音中定时采样用）
  const apiRef = useRef({ getWavSnapshot });
  apiRef.current.getWavSnapshot = getWavSnapshot;
  useEffect(() => { onApiReady?.(apiRef.current); }, [onApiReady]);

  // Canvas 径向波形动画
  useEffect(() => {
    if (!isActive) {
      const canvas = canvasRef.current;
      if (canvas) {
        const ctx = canvas.getContext('2d');
        if (ctx) ctx.clearRect(0, 0, canvasSize, canvasSize);
      }
      cancelAnimationFrame(rafRef.current);
      return;
    }

    const draw = () => {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const dpr = window.devicePixelRatio || 1;
      canvas.width = canvasSize * dpr;
      canvas.height = canvasSize * dpr;
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, canvasSize, canvasSize);

      const cx = canvasSize / 2;
      const cy = canvasSize / 2;
      const baseR = size / 2 + 4;
      const bins = freqData.length;
      const barCount = Math.min(bins, 32);
      const step = Math.floor(bins / barCount);

      for (let i = 0; i < barCount; i++) {
        const val = freqData[i * step] / 255;
        const barH = 3 + val * 10;
        const angle = (i / barCount) * Math.PI * 2 - Math.PI / 2;

        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate(angle);

        const alpha = 0.3 + val * 0.5;
        ctx.fillStyle = `rgba(65, 118, 230, ${alpha})`;
        ctx.beginPath();
        ctx.roundRect(-1.5, baseR, 3, barH, 1.5);
        ctx.fill();
        ctx.restore();
      }

      rafRef.current = requestAnimationFrame(draw);
    };

    rafRef.current = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(rafRef.current);
  }, [isActive, freqData, size, canvasSize]);

  if (!isSupported) return null;

  const handleClick = async () => {
    if (isActive) {
      // 停止 → 取 WAV → 回调父组件（录音时长 <300ms 视为误触，丢弃）
      // 时序注意：先 stopAndGetWav()（ctx 未关时采集结果）再 toggle()（真正停止），顺序不能反
      const wav = await stopAndGetWav();
      await toggle();
      if (wav && onRecorded) onRecorded(wav);
    } else {
      await toggle();
    }
  };

  // 录音上限 60s：超时自动停止并触发识别（防麦克风长期占用 + 内存无限累积）
  useEffect(() => {
    if (!isActive) return;
    const timer = setTimeout(() => { void handleClick(); }, 60_000);
    return () => clearTimeout(timer);
  }, [isActive]);

  return (
    <div style={{
      position: 'relative',
      width: canvasSize,
      height: canvasSize,
      flexShrink: 0,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
    }}>
      {/* 波形 canvas */}
      <canvas
        ref={canvasRef}
        style={{
          position: 'absolute',
          inset: 0,
          width: canvasSize,
          height: canvasSize,
          pointerEvents: 'none',
          opacity: isActive ? 1 : 0,
          transition: 'opacity 0.2s',
        }}
      />

      {/* 麦克风按钮 */}
      <button
        onClick={handleClick}
        disabled={disabled}
        title={isActive ? '停止录音' : '开始录音'}
        aria-label={isActive ? '停止录音' : '开始录音'}
        style={{
          position: 'relative',
          zIndex: 1,
          width: size,
          height: size,
          borderRadius: 12,
          border: 'none',
          background: isActive ? DS_BLUE : 'transparent',
          color: isActive ? '#fff' : 'rgb(84, 85, 87)',
          cursor: disabled ? 'not-allowed' : 'pointer',
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          transition: 'transform 0.08s ease-out, background 0.2s, color 0.2s',
          transform: isActive ? `scale(${1 + volume * 0.25})` : 'scale(1)',
        }}
      >
        <svg
          width={isMobile ? 22 : 18}
          height={isMobile ? 22 : 18}
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <rect x="9" y="2" width="6" height="12" rx="3" />
          <path d="M5 10a7 7 0 0 0 14 0" />
          <line x1="12" y1="19" x2="12" y2="22" />
        </svg>
      </button>
    </div>
  );
}
