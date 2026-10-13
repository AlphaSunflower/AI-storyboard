/**
 * /chat「生成偏好」的本地模型（即梦式）：自动 / 自定义两态 + 图片、视频各自的
 * 比例、清晰度、模型，外加一个独立开关「忽略对话中的生成方案」。
 *
 * 偏好存浏览器本地（两个开关都记住），每轮随请求带给 MoonAgent；自动且不忽略方案
 * 时整体不带（保持既有零回归行为）。模型能力（可选比例、清晰度标签与取值键）由后端
 * /ai/models 归一化下发，缺省时这里按同一规则本地兜底推导。
 */

export type MediaKind = 'image' | 'video';

/** 清晰度对应的网关参数键：图片为尺寸或质量，视频为分辨率。 */
export type ClarityParam = 'size' | 'quality' | 'resolution';

export interface MediaGenerationPreference {
  /** 模型值；'' = 跟随网关默认模型 */
  model: string;
  /** 比例；'' = 智能（不下发，交给智能体决定） */
  aspectRatio: string;
  /** 清晰度取值；'' = 跟随模型默认 */
  clarity: string;
  /** 清晰度取值对应的参数键；'' = 该模型无清晰度能力 */
  clarityParam: ClarityParam | '';
}

export interface GenerationPreferences {
  /** 自动：智能体自行决定比例 / 清晰度 / 模型（默认开） */
  auto: boolean;
  /** 忽略对话中的生成方案：媒体生成直接执行，不再出方案确认卡 */
  ignoreGenerationPlan: boolean;
  image: MediaGenerationPreference;
  video: MediaGenerationPreference;
}

/** 模型能力块（后端 /ai/models 下发的 preferences 字段结构） */
export interface ModelPreferenceCapability {
  ratioOptions: string[];
  ratioDefault: string;
  clarity: {
    label: string;
    param: ClarityParam;
    options: string[];
    default: string;
  } | null;
}

export const GENERATION_PREFERENCES_STORAGE_KEY = 'moon.generationPreferences.v1';

/** 智能比例：交给智能体决定，不下发任何比例参数。 */
export const SMART_RATIO = '';

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => text(item)).filter((item) => item !== '');
}

function emptyMediaPreference(): MediaGenerationPreference {
  return { model: '', aspectRatio: SMART_RATIO, clarity: '', clarityParam: '' };
}

export function defaultGenerationPreferences(): GenerationPreferences {
  return {
    auto: true,
    ignoreGenerationPlan: false,
    image: emptyMediaPreference(),
    video: emptyMediaPreference(),
  };
}

function normalizeMedia(raw: unknown): MediaGenerationPreference {
  const source = (raw ?? {}) as Record<string, unknown>;
  const param = text(source.clarityParam);
  const clarityParam: ClarityParam | '' =
    param === 'size' || param === 'quality' || param === 'resolution' ? param : '';
  return {
    model: text(source.model),
    aspectRatio: text(source.aspectRatio),
    clarity: clarityParam ? text(source.clarity) : '',
    clarityParam,
  };
}

/** 读取结果一律做形状校验：脏数据/旧版本回落默认，不抛错。 */
export function normalizeGenerationPreferences(raw: unknown): GenerationPreferences {
  const base = defaultGenerationPreferences();
  if (!raw || typeof raw !== 'object') return base;
  const source = raw as Record<string, unknown>;
  return {
    auto: typeof source.auto === 'boolean' ? source.auto : base.auto,
    ignoreGenerationPlan:
      typeof source.ignoreGenerationPlan === 'boolean'
        ? source.ignoreGenerationPlan
        : base.ignoreGenerationPlan,
    image: normalizeMedia(source.image),
    video: normalizeMedia(source.video),
  };
}

export function loadGenerationPreferences(): GenerationPreferences {
  try {
    const raw = window.localStorage.getItem(GENERATION_PREFERENCES_STORAGE_KEY);
    if (!raw) return defaultGenerationPreferences();
    return normalizeGenerationPreferences(JSON.parse(raw) as unknown);
  } catch {
    // 隐私模式 / 脏 JSON：回落默认，偏好不应阻断输入卡
    return defaultGenerationPreferences();
  }
}

export function saveGenerationPreferences(preferences: GenerationPreferences): void {
  try {
    window.localStorage.setItem(
      GENERATION_PREFERENCES_STORAGE_KEY,
      JSON.stringify(normalizeGenerationPreferences(preferences)),
    );
  } catch {
    // 存储不可用（配额/隐私模式）：本次会话内存态仍生效
  }
}

/** 单类媒体的请求体：只带非空项，空值 = 交给智能体/模型默认。 */
function mediaPayload(preference: MediaGenerationPreference): Record<string, string> {
  const payload: Record<string, string> = {};
  if (preference.model) payload.model = preference.model;
  if (preference.aspectRatio) payload.aspectRatio = preference.aspectRatio;
  if (preference.clarity && preference.clarityParam) {
    payload[preference.clarityParam] = preference.clarity;
  }
  return payload;
}

/**
 * 随 turn 请求下发的偏好载荷。
 * 自动且不忽略生成方案时返回 undefined：请求体里不带该字段（零回归）。
 */
export function toGenerationPreferencesPayload(
  preferences: GenerationPreferences,
): Record<string, unknown> | undefined {
  const normalized = normalizeGenerationPreferences(preferences);
  if (normalized.auto && !normalized.ignoreGenerationPlan) return undefined;
  return {
    auto: normalized.auto,
    ignoreGenerationPlan: normalized.ignoreGenerationPlan,
    image: mediaPayload(normalized.image),
    video: mediaPayload(normalized.video),
  };
}

/** 本地兜底推导模型能力：后端未下发 preferences 时按同一规则计算。 */
export function deriveModelCapability(params: unknown, kind: MediaKind): ModelPreferenceCapability | null {
  // 卡片路径下 params 是后端透传的 JSON 字符串，偏好弹窗路径下已是解析后的对象
  let source: Record<string, unknown> | null = null;
  if (typeof params === 'string') {
    try {
      const parsed: unknown = JSON.parse(params);
      source = parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
    } catch {
      source = null;
    }
  } else if (params && typeof params === 'object') {
    source = params as Record<string, unknown>;
  }
  if (!source) return null;
  const ratioOptions = stringList(source.aspectRatios);
  const capability: ModelPreferenceCapability = {
    ratioOptions,
    ratioDefault: text(source.aspectRatioDefault),
    clarity: null,
  };
  if (kind === 'image') {
    // 图片优先尺寸档位；模型只声明质量时退回质量，标签随之变化
    const sizes = stringList(source.sizes);
    if (sizes.length > 0) {
      capability.clarity = {
        label: '清晰度',
        param: 'size',
        options: sizes,
        default: text(source.sizeDefault),
      };
      return capability;
    }
    const qualities = stringList(source.qualities);
    if (qualities.length > 0) {
      capability.clarity = {
        label: '质量',
        param: 'quality',
        options: qualities,
        default: text(source.qualityDefault),
      };
    }
    return capability;
  }
  const resolutions = stringList(source.resolutions);
  if (resolutions.length > 0) {
    capability.clarity = {
      label: '清晰度',
      param: 'resolution',
      options: resolutions,
      default: text(source.resolutionDefault),
    };
  }
  return capability;
}

/** 后端下发的 preferences 能力块校验（形状不对时返回 null，走本地兜底）。 */
export function readModelCapability(
  model: { preferences?: unknown; params?: unknown } | undefined,
  kind: MediaKind,
): ModelPreferenceCapability | null {
  const raw = model?.preferences;
  if (raw && typeof raw === 'object') {
    const source = raw as Record<string, unknown>;
    const clarityRaw = source.clarity as Record<string, unknown> | null | undefined;
    const clarityParam = text(clarityRaw?.param);
    const clarity = clarityRaw && typeof clarityRaw === 'object' &&
      (clarityParam === 'size' || clarityParam === 'quality' || clarityParam === 'resolution')
      ? {
          label: text(clarityRaw.label) || '清晰度',
          param: clarityParam as ClarityParam,
          options: stringList(clarityRaw.options),
          default: text(clarityRaw.default),
        }
      : null;
    return {
      ratioOptions: stringList(source.ratioOptions),
      ratioDefault: text(source.ratioDefault),
      clarity: clarity && clarity.options.length > 0 ? clarity : null,
    };
  }
  return deriveModelCapability(model?.params, kind);
}

/** 计划卡参数选择器的预选值（键带 image/video 前缀，与 AgentParamSelector 对齐）。 */
export function buildPlanCardParams(
  kind: MediaKind,
  preference: MediaGenerationPreference,
  capability: ModelPreferenceCapability | null,
  prefix: string = kind,
): Record<string, string> {
  const params: Record<string, string> = {};
  if (preference.model) params[`${prefix}Model`] = preference.model;
  if (preference.aspectRatio) params[`${prefix}AspectRatio`] = preference.aspectRatio;
  // 清晰度键优先用用户选择时记下的参数键；缺失时按模型能力兜底
  const clarityParam = preference.clarityParam || capability?.clarity?.param || '';
  if (preference.clarity && clarityParam) {
    params[`${prefix}${clarityParam.charAt(0).toUpperCase()}${clarityParam.slice(1)}`] = preference.clarity;
  }
  return params;
}

/**
 * 对话里「生成方案」卡片（分镜确认卡 / 视频方案卡）的预选参数：
 * 自动模式不预选（保持既有推荐逻辑），自定义模式按偏好预选，用户仍可临时改。
 */
export function planCardPrefill(
  preferences: GenerationPreferences,
  kind: MediaKind,
  models: { value: string; default?: string | boolean; params?: unknown; preferences?: unknown }[] | undefined,
  prefix: string = kind,
): Record<string, string> {
  if (preferences.auto) return {};
  const choice = preferences[kind];
  const list = models ?? [];
  const model =
    list.find((item) => item.value === choice.model) ??
    list.find((item) => item.default === 'true' || item.default === true) ??
    list[0];
  return buildPlanCardParams(kind, choice, readModelCapability(model, kind), prefix);
}
