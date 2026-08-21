import { useState, useRef, useCallback, useEffect } from 'react';

/** 16kHz 单声道 16bit WAV 组装（44 字节标准头） */
function encodeWav(samples: Float32Array, sampleRate: number): Blob {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const writeStr = (off: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i));
  };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + samples.length * 2, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);            // PCM
  view.setUint16(22, 1, true);            // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);           // 16bit
  writeStr(36, 'data');
  view.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Blob([buffer], { type: 'audio/wav' });
}

/** 把任意采样率 Float32 重采样为 16kHz（线性插值，语音场景足够） */
function resampleTo16k(input: Float32Array, fromRate: number): Float32Array {
  if (fromRate === 16000) return input;
  const ratio = fromRate / 16000;
  const outLen = Math.floor(input.length / ratio);
  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const pos = i * ratio;
    const idx = Math.floor(pos);
    const frac = pos - idx;
    const a = input[idx] ?? 0;
    const b = input[idx + 1] ?? a;
    out[i] = a + (b - a) * frac;
  }
  return out;
}

/** Web Audio API 麦克风音量 hook —— 返回实时音量频域数据 */
export function useMicVolume(fftSize = 64) {
  const [volume, setVolume] = useState(0);
  const [isActive, setIsActive] = useState(false);
  const [freqData, setFreqData] = useState<number[]>(new Array(fftSize / 2).fill(0));

  const ctxRef = useRef<AudioContext | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const rafRef = useRef(0);
  const bufRef = useRef(new Uint8Array(fftSize / 2));
  const chunksRef = useRef<Float32Array[]>([]);
  const recorderRef = useRef<ScriptProcessorNode | null>(null);

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
      const ctx = new AudioContext();
      const analyser = ctx.createAnalyser();
      analyser.fftSize = fftSize;
      // 清空上一轮录音残留
      chunksRef.current = [];
      // ScriptProcessorNode 采集 PCM（与 analyser 并行接同一 source，波形可视化路径不变）
      const recorder = ctx.createScriptProcessor(4096, 1, 1);
      recorder.onaudioprocess = (e) => {
        const ch = e.inputBuffer.getChannelData(0);
        chunksRef.current.push(new Float32Array(ch)); // 拷贝副本，避免引用同一缓冲
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

  // 停止录音并返回 16kHz 单声道 WAV Blob（无数据返回 null）
  const stopAndGetWav = useCallback(async (): Promise<Blob | null> => {
    const chunks = chunksRef.current;
    chunksRef.current = [];
    if (chunks.length === 0) return null;
    let total = 0;
    for (const c of chunks) total += c.length;
    const raw = new Float32Array(total);
    let off = 0;
    for (const c of chunks) { raw.set(c, off); off += c.length; }
    const srcRate = ctxRef.current?.sampleRate ?? 48000;
    const samples = resampleTo16k(raw, srcRate);
    return encodeWav(samples, 16000);
  }, []);

  return { volume, freqData, isActive, isSupported, toggle, stopAndGetWav };
}
