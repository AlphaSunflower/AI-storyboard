import { useState, useRef, useCallback, useEffect } from 'react';

/** Web Audio API 麦克风音量 hook —— 返回实时音量频域数据 */
export function useMicVolume(fftSize = 64, onPcm?: (pcm16k: Int16Array) => void) {
  const [volume, setVolume] = useState(0);
  const [isActive, setIsActive] = useState(false);
  const [freqData, setFreqData] = useState<number[]>(new Array(fftSize / 2).fill(0));

  const ctxRef = useRef<AudioContext | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const rafRef = useRef(0);
  const bufRef = useRef(new Uint8Array(fftSize / 2));
  const recorderRef = useRef<ScriptProcessorNode | null>(null);
  // onPcm 回调存 ref：MicButton 挂载后传入，避免 toggle 闭包捕获旧值
  const onPcmRef = useRef(onPcm);
  onPcmRef.current = onPcm;

  const isSupported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;

  const tick = useCallback(() => {
    const analyser = analyserRef.current;
    if (!analyser) return;
    analyser.getByteFrequencyData(bufRef.current);
    const arr = bufRef.current;
    let sum = 0;
    for (let i = 0; i < arr.length; i++) sum += arr[i];
    setVolume(sum / arr.length / 255);
    setFreqData(Array.from(arr));
    rafRef.current = requestAnimationFrame(tick);
  }, []);

  const stop = useCallback(() => {
    cancelAnimationFrame(rafRef.current);
    streamRef.current?.getTracks().forEach((t) => t.stop());
    recorderRef.current?.disconnect();
    recorderRef.current = null;
    streamRef.current = null;
    ctxRef.current?.close().catch(() => {});
    ctxRef.current = null;
    analyserRef.current = null;
    setIsActive(false);
    setVolume(0);
    setFreqData(new Array(fftSize / 2).fill(0));
  }, [fftSize]);

  const toggle = useCallback(async () => {
    if (isActive) { stop(); return; }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      // 指定 16kHz：浏览器自动重采样，ScriptProcessor 每块即 16k 采样，零手动重采样
      const ctx = new AudioContext({ sampleRate: 16000 } as AudioContextOptions);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = fftSize;
      // ScriptProcessorNode 采集 PCM（与 analyser 并行接同一 source，波形可视化路径不变）
      const recorder = ctx.createScriptProcessor(4096, 1, 1);
      recorder.onaudioprocess = (e) => {
        const ch = e.inputBuffer.getChannelData(0);
        // 流式：Float32 → Int16 实时推给父组件（sttStream.push，vosk WS 直连）
        const onPcm = onPcmRef.current;
        if (onPcm) {
          const int16 = new Int16Array(ch.length);
          for (let i = 0; i < ch.length; i++) {
            const s = Math.max(-1, Math.min(1, ch[i]));
            int16[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
          }
          onPcm(int16);
        }
      };
      const source = ctx.createMediaStreamSource(stream);
      source.connect(analyser);
      source.connect(recorder);
      recorder.connect(ctx.destination); // 必须连 destination 才会触发处理
      recorderRef.current = recorder;
      ctxRef.current = ctx;
      streamRef.current = stream;
      analyserRef.current = analyser;
      bufRef.current = new Uint8Array(analyser.frequencyBinCount);
      setIsActive(true);
      rafRef.current = requestAnimationFrame(tick);
    } catch {
      // 权限被拒，静默降级
    }
  }, [isActive, fftSize, tick, stop]);

  useEffect(() => () => stop(), [stop]);

  return { volume, freqData, isActive, isSupported, toggle };
}
