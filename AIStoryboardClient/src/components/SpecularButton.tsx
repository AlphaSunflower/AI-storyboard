import { useRef, useEffect } from 'react';
import type { CSSProperties, ReactNode, MouseEventHandler } from 'react';
import { Renderer, Program, Mesh, Triangle } from 'ogl';
import './SpecularButton.css';

type ButtonSize = 'sm' | 'md' | 'lg';

export interface SpecularButtonProps {
  children?: ReactNode;
  size?: ButtonSize;
  radius?: number;
  tint?: string;
  tintOpacity?: number;
  blur?: number;
  textColor?: string;
  lineColor?: string;
  baseColor?: string;
  intensity?: number;
  shineSize?: number;
  shineFade?: number;
  thickness?: number;
  speed?: number;
  followMouse?: boolean;
  proximity?: number;
  autoAnimate?: boolean;
  disabled?: boolean;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  className?: string;
  type?: 'button' | 'submit' | 'reset';
}

interface ShaderProps {
  radius: number;
  lineColor: string;
  baseColor: string;
  intensity: number;
  shineSize: number;
  shineFade: number;
  thickness: number;
  speed: number;
  followMouse: boolean;
  proximity: number;
  autoAnimate: boolean;
}

const PAD = 20;

const VERT = `#version 300 es
in vec2 position;
void main() {
  gl_Position = vec4(position, 0.0, 1.0);
}
`;

const FRAG = `#version 300 es
precision highp float;

uniform vec2 uCenter;
uniform vec2 uHalfSize;
uniform float uRadius;
uniform float uAngle;
uniform float uPx;
uniform vec3 uLineColor;
uniform vec3 uBaseColor;
uniform float uIntensity;
uniform float uShineSize;
uniform float uShineFade;
uniform float uThickness;
uniform float uBaseWidth;

out vec4 fragColor;

float sdRoundedRect(vec2 p, vec2 b, float r) {
  vec2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float shapeSDF(vec2 p) { return sdRoundedRect(p, uHalfSize, uRadius); }

float gaussianLine(float d, float sigma) {
  float x = d / (sigma + 1e-6);
  float k = mix(1.0, 1.6, smoothstep(0.0, 1.5, x));
  return exp(-k * x * x);
}

void main() {
  vec2 p = gl_FragCoord.xy - uCenter;
  float d = shapeSDF(p);
  vec2 L = vec2(cos(uAngle), sin(uAngle));

  // Dark base stroke hugging the edge for a sense of thickness
  float base = (1.0 - smoothstep(0.0, uBaseWidth, abs(d))) * 0.45;

  // Symmetric specular: the edges facing toward/away from the light both
  // catch a streak. The angular window (size + fade) is measured with an
  // elliptical normal so it varies continuously along straight edges.
  vec2 nEll = normalize(p / (uHalfSize * uHalfSize) + 1e-6);
  float phi = acos(clamp(abs(dot(nEll, L)), 0.0, 1.0));
  float rim = 1.0 - smoothstep(uShineSize - uShineFade, uShineSize + uShineFade + 1e-4, phi);
  float line = gaussianLine(d, uThickness);
  float edgeClamp = 1.0 - smoothstep(0.5 * uPx, 3.0 * uPx, abs(d));
  float hi = line * rim * edgeClamp * uIntensity;

  vec3 col = uBaseColor * base + uLineColor * hi;
  float a = clamp(base + hi, 0.0, 1.0);
  fragColor = vec4(col, a);
}
`;

type RGB = [number, number, number];

/**
 * 解析 CSS 颜色字符串为 0..1 的 RGB。
 *
 * 只处理规整格式（hex / rgb() / rgba() / color(srgb ...)）；`var(--token)`、
 * `color-mix()` 这类需要浏览器计算的写法交给 {@link resolveColor} 用探针元素解析。
 * 绝不在每帧调用：ogl 的 `Color#set` 遇到不认识的写法会 `console.warn`
 * （“Color format not recognised”），而它原来正跑在 requestAnimationFrame 里，
 * 于是每秒刷出上百条 warning，把 devtools 和页面一起拖死。
 */
function parseStaticColor(value: string): RGB | null {
  const v = value.trim();
  if (v.startsWith('#')) {
    const hex = v.slice(1);
    if (!/^([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(hex)) return null;
    const full = hex.length <= 4 ? hex.split('').map((c) => c + c).join('') : hex;
    return [
      parseInt(full.slice(0, 2), 16) / 255,
      parseInt(full.slice(2, 4), 16) / 255,
      parseInt(full.slice(4, 6), 16) / 255,
    ];
  }
  const rgb = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/i.exec(v);
  if (rgb) return [Number(rgb[1]) / 255, Number(rgb[2]) / 255, Number(rgb[3]) / 255];
  const srgb = /^color\(\s*srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)/i.exec(v);
  if (srgb) return [Number(srgb[1]), Number(srgb[2]), Number(srgb[3])];
  return null;
}

/** 探针哨兵色：赋了非法颜色时浏览器会保留上一个值，用它区分“解析成功”与“解析失败”。 */
const SENTINEL = 'rgb(1, 2, 3)';

const SpecularButton = ({
  children = 'Get Started',
  size = 'lg',
  radius = 18,
  tint = '#ffffff',
  tintOpacity = 0,
  blur = 0,
  textColor = '#f5f5f5',
  lineColor = '#ffffff',
  baseColor = '#525252',
  intensity = 1,
  shineSize = 10,
  shineFade = 40,
  thickness = 1,
  speed = 0.35,
  followMouse = true,
  proximity = 250,
  autoAnimate = false,
  disabled = false,
  onClick,
  className = '',
  type = 'button'
}: SpecularButtonProps) => {
  const btnRef = useRef<HTMLButtonElement>(null);
  const fxRef = useRef<HTMLSpanElement>(null);
  const propsRef = useRef<ShaderProps>({} as ShaderProps);

  propsRef.current = { radius, lineColor, baseColor, intensity, shineSize, shineFade, thickness, speed, followMouse, proximity, autoAnimate };

  useEffect(() => {
    const btn = btnRef.current;
    const fx = fxRef.current;
    if (!btn || !fx) return;

    const dpr = window.devicePixelRatio || 1;
    let renderer!: Renderer;
    let gl!: Renderer['gl'];
    try {
      renderer = new Renderer({ alpha: true, premultipliedAlpha: true, antialias: true, dpr });
      gl = renderer.gl;
    } catch {
      // WebGL 不可用，退化为无特效按钮
      return;
    }
    gl.clearColor(0, 0, 0, 0);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

    // 颜色探针：挂在按钮内部（而不是 body），这样 var(--sb-*) 等局部变量也能解析。
    const probe = document.createElement('span');
    probe.setAttribute('aria-hidden', 'true');
    probe.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;visibility:hidden;pointer-events:none;';
    fx.appendChild(probe);

    // 颜色只按“字符串变化”解析一次：每帧解析是原来的主要开销与 warning 来源。
    const colorCache = new Map<string, RGB>();
    const resolveColor = (value: string): RGB => {
      const key = value ?? '';
      const cached = colorCache.get(key);
      if (cached) return cached;
      let out = parseStaticColor(key);
      if (!out) {
        probe.style.color = SENTINEL;
        probe.style.color = key;
        const computed = window.getComputedStyle(probe).color;
        out = computed && computed !== SENTINEL ? parseStaticColor(computed) : null;
      }
      const resolved = out ?? [0, 0, 0];
      colorCache.set(key, resolved);
      return resolved;
    };

    const geometry = new Triangle(gl);
    if (geometry.attributes.uv) delete geometry.attributes.uv;

    const program = new Program(gl, {
      vertex: VERT,
      fragment: FRAG,
      uniforms: {
        uCenter: { value: [0, 0] },
        uHalfSize: { value: [1, 1] },
        uRadius: { value: 0 },
        uAngle: { value: 2.4 },
        uPx: { value: dpr },
        uLineColor: { value: [1, 1, 1] },
        uBaseColor: { value: [0.32, 0.32, 0.32] },
        uIntensity: { value: 1 },
        uShineSize: { value: 0.17 },
        uShineFade: { value: 0.7 },
        uThickness: { value: 1 },

        uBaseWidth: { value: dpr }
      }
    });

    const mesh = new Mesh(gl, { geometry, program });
    fx.appendChild(gl.canvas);

    // 位置缓存：pointermove 里读 getBoundingClientRect 会强制同步布局，
    // 多实例叠加时每一次鼠标移动都是成倍的 reflow。改成事件只记坐标、
    // 每帧最多校准一次矩形（resize / scroll 时标脏）。
    let rect = btn.getBoundingClientRect();
    let rectDirty = false;
    const sizeRef = { w: 1, h: 1 };
    const resize = () => {
      rect = btn.getBoundingClientRect();
      rectDirty = false;
      const w = rect.width;
      const h = rect.height;
      sizeRef.w = w;
      sizeRef.h = h;
      renderer.setSize(w + PAD * 2, h + PAD * 2);
      program.uniforms.uCenter.value = [(PAD + w / 2) * dpr, (PAD + h / 2) * dpr];
      program.uniforms.uHalfSize.value = [(w / 2) * dpr, (h / 2) * dpr];
    };
    const ro = new ResizeObserver(resize);
    ro.observe(btn);
    resize();

    // Light angle steers toward the pointer (anywhere on the page) and falls
    // back to a slow sweep when the pointer hasn't moved yet.
    let pointer: { x: number; y: number } | null = null;
    let pointerAngle: number | null = null;
    let proximityT = 0;
    const onPointerMove = (e: PointerEvent) => {
      pointer = { x: e.clientX, y: e.clientY };
    };
    const onScroll = () => { rectDirty = true; };
    window.addEventListener('pointermove', onPointerMove, { passive: true });
    window.addEventListener('scroll', onScroll, { passive: true, capture: true });

    // 不可见（滚出视口 / 切到后台标签页）时不做任何 GL 工作：
    // 编辑器与文档页会同时挂几个实例，原来的无限渲染是这类页面的常见卡顿源。
    let inView = true;
    const io = typeof IntersectionObserver === 'undefined'
      ? null
      : new IntersectionObserver((entries) => {
        const last = entries[entries.length - 1];
        if (last) inView = last.isIntersecting;
      });
    io?.observe(btn);

    let angle = 2.4;
    let idleAngle = 2.4;
    let bright = 0;
    let last = performance.now();
    let raf = 0;
    let lastLineColor = '';
    let lastBaseColor = '';

    const lineRGB: RGB = [1, 1, 1];
    const baseRGB: RGB = [0.32, 0.32, 0.32];

    const update = (now: number) => {
      raf = requestAnimationFrame(update);
      const dt = Math.min((now - last) / 1000, 0.05);
      last = now;
      if (document.hidden || !inView) return;

      if (rectDirty) {
        rect = btn.getBoundingClientRect();
        rectDirty = false;
      }

      if (pointer) {
        const e = pointer;
        const dx = Math.max(rect.left - e.x, 0, e.x - rect.right);
        const dy = Math.max(rect.top - e.y, 0, e.y - rect.bottom);
        const dist = Math.hypot(dx, dy);
        // Over the button itself the light settles on the diagonal (framing the
        // corners) and gently sways with the cursor position within the button.
        if (dist === 0) {
          const cx = rect.left + rect.width / 2;
          const cy = rect.top + rect.height / 2;
          const nx = (e.x - cx) / (rect.width / 2);
          const ny = (cy - e.y) / (rect.height / 2);
          pointerAngle = Math.atan2(2 / rect.height, -2 / rect.width) + nx * 0.3 + ny * 0.15;
        } else {
          const cx = rect.left + rect.width / 2;
          const cy = rect.top + rect.height / 2;
          pointerAngle = Math.atan2(cy - e.y, e.x - cx);
        }
        const t = Math.max(0, 1 - dist / Math.max(propsRef.current.proximity, 1));
        proximityT = t * t * (3 - 2 * t);
      }

      const p = propsRef.current;

      idleAngle += p.speed * dt;
      const steer = p.followMouse && pointerAngle != null && (!p.autoAnimate || proximityT > 0);
      const target = steer && pointerAngle != null ? pointerAngle : idleAngle;
      const diff = ((target - angle + Math.PI * 3) % (Math.PI * 2)) - Math.PI;
      angle += diff * (1 - Math.exp(-dt * 7));

      // Shine fades in with pointer proximity unless autoAnimate keeps it on
      const brightTarget = p.autoAnimate ? 1 : proximityT;
      bright += (brightTarget - bright) * (1 - Math.exp(-dt * 8));

      if (p.lineColor !== lastLineColor) {
        const rgb = resolveColor(p.lineColor);
        lineRGB[0] = rgb[0]; lineRGB[1] = rgb[1]; lineRGB[2] = rgb[2];
        lastLineColor = p.lineColor;
      }
      if (p.baseColor !== lastBaseColor) {
        const rgb = resolveColor(p.baseColor);
        baseRGB[0] = rgb[0]; baseRGB[1] = rgb[1]; baseRGB[2] = rgb[2];
        lastBaseColor = p.baseColor;
      }

      program.uniforms.uAngle.value = angle;
      program.uniforms.uRadius.value = Math.min(p.radius, Math.min(sizeRef.w, sizeRef.h) / 2) * dpr;
      program.uniforms.uLineColor.value = lineRGB;
      program.uniforms.uBaseColor.value = baseRGB;
      program.uniforms.uIntensity.value = p.intensity * bright;
      program.uniforms.uShineSize.value = (p.shineSize * Math.PI) / 180;
      program.uniforms.uShineFade.value = (p.shineFade * Math.PI) / 180;
      program.uniforms.uThickness.value = p.thickness * dpr;
      renderer.render({ scene: mesh });
    };
    raf = requestAnimationFrame(update);

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      io?.disconnect();
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('scroll', onScroll, { capture: true } as EventListenerOptions);
      if (gl.canvas.parentNode === fx) fx.removeChild(gl.canvas);
      if (probe.parentNode === fx) fx.removeChild(probe);
      gl.getExtension('WEBGL_lose_context')?.loseContext();
    };
  }, []);

  return (
    <button
      ref={btnRef}
      type={type}
      disabled={disabled}
      onClick={onClick}
      className={`specular-button specular-button--${size}${className ? ` ${className}` : ''}`}
      style={
        {
          '--sb-radius': `${radius}px`,
          '--sb-tint': tint,
          '--sb-tint-opacity': tintOpacity,
          '--sb-blur': `${blur}px`,
          '--sb-text-color': textColor
        } as CSSProperties
      }
    >
      <span ref={fxRef} className="specular-button__fx" aria-hidden="true" />
      <span className="specular-button__label">{children}</span>
    </button>
  );
};

export default SpecularButton;
