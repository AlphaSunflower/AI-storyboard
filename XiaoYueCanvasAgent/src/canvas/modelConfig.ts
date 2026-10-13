import type { CanvasNode } from '../types';

export type ModelConfigValue =
  | string
  | number
  | boolean
  | ModelConfigValue[]
  | { [key: string]: ModelConfigValue };

export interface ModelConfigField {
  key: string;
  label: string;
  type: string;
  options?: Array<string | number>;
  min?: number;
  max?: number;
  default?: ModelConfigValue;
  show?: boolean;
  role?: string;
  cardinality?: string;
  fields?: ModelConfigField[];
}

export interface ModelConfigModel {
  value: string;
  label: string;
  isDefault: boolean;
  fields: ModelConfigField[];
  defaults: Record<string, ModelConfigValue>;
}

export class ModelConfigValidationError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly path?: string,
  ) {
    super(message);
    this.name = 'ModelConfigValidationError';
  }
}

export const MODEL_CONFIG_RESERVED_KEYS = new Set([
  'model',
  'gatewayParams',
  'size',
  'quality',
  'style',
  'aspectRatio',
  'aspect_ratio',
  'n',
  'duration',
  'resolution',
]);

const MODEL_CONFIG_NODE_TYPES: Record<string, 'image' | 'video'> = {
  imageGeneration: 'image',
  videoGeneration: 'video',
  imageGen: 'image',
  textToImage: 'image',
  imageToImage: 'image',
  batchImageRefine: 'image',
  videoGen: 'video',
  textToVideo: 'video',
  imageToVideo: 'video',
  firstLastFrameToVideo: 'video',
  referenceToVideo: 'video',
};

const DEFAULT_ALIASES: Record<string, string> = {
  hd: 'high',
  standard: 'medium',
};

const normalizeEnumDefault = (
  value: unknown,
  options: Array<string | number> | undefined,
): unknown => {
  if (!options?.length || value === undefined || value === null) return value;
  const raw = String(value);
  const candidate = DEFAULT_ALIASES[raw.toLowerCase()] ?? raw;
  return options.some((option) => String(option) === candidate) ? candidate : options[0];
};

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;

const asNumber = (value: unknown): number | undefined => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
};

const parseParams = (raw: unknown): Record<string, unknown> => {
  if (typeof raw === 'string') {
    try {
      return asRecord(JSON.parse(raw)) ?? {};
    } catch {
      return {};
    }
  }
  return asRecord(raw) ?? {};
};

const fieldType = (raw: Record<string, unknown>): string => {
  const type = typeof raw.type === 'string' ? raw.type : '';
  if (type === 'boolean') return 'bool';
  if (type === 'enum' || type === 'range' || type === 'number' || type === 'bool'
    || type === 'string' || type === 'object' || type === 'array' || type === 'arrayofobject') {
    return type;
  }
  if (Array.isArray(raw.options)) return 'enum';
  if (raw.default === true || raw.default === false) return 'bool';
  if (typeof raw.default === 'number') return 'number';
  return 'string';
};

const normalizeField = (raw: Record<string, unknown>): ModelConfigField | undefined => {
  const key = typeof raw.key === 'string' ? raw.key.trim() : '';
  if (!key) return undefined;
  const nested = Array.isArray(raw.fields)
    ? raw.fields.map((item) => normalizeField(asRecord(item) ?? {})).filter((item): item is ModelConfigField => Boolean(item))
    : undefined;
  const options = Array.isArray(raw.options)
    ? raw.options.filter((item): item is string | number => typeof item === 'string' || typeof item === 'number')
    : undefined;
  const type = fieldType(raw);
  const normalizedDefault = type === 'enum' || options?.length
    ? normalizeEnumDefault(raw.default, options)
    : raw.default;
  const field: ModelConfigField = {
    key,
    label: typeof raw.label === 'string' && raw.label.trim() ? raw.label.trim() : key,
    type: options?.length && type === 'string' ? 'enum' : type,
    show: raw.show !== false,
  };
  if (options?.length) field.options = options;
  if (typeof raw.role === 'string') field.role = raw.role;
  if (typeof raw.cardinality === 'string') field.cardinality = raw.cardinality;
  if (typeof raw.min === 'number') field.min = raw.min;
  if (typeof raw.max === 'number') field.max = raw.max;
  if (normalizedDefault !== undefined) field.default = normalizedDefault as ModelConfigValue;
  if (nested?.length) field.fields = nested;
  return field;
};

const legacyFields = (params: Record<string, unknown>): ModelConfigField[] => {
  const fields: ModelConfigField[] = [];
  const n = asRecord(params.n);
  if (n) {
    fields.push({
      key: 'n',
      label: 'n',
      type: 'range',
      min: asNumber(n.min),
      max: asNumber(n.max),
      default: asNumber(n.default),
      show: true,
    });
  }
  const listField = (
    key: string,
    rawList: unknown,
    defaultKey: string,
  ): ModelConfigField | undefined => {
    const options = Array.isArray(rawList)
      ? rawList.filter((item): item is string | number => typeof item === 'string' || typeof item === 'number')
      : typeof rawList === 'string'
        ? rawList.split(',').map((item) => item.trim()).filter(Boolean)
        : [];
    if (!options.length) return undefined;
    const fallback = params[defaultKey];
    return {
      key,
      label: key,
      type: 'enum',
      options,
      default: normalizeEnumDefault(
        (typeof fallback === 'string' || typeof fallback === 'number') ? fallback : options[0],
        options,
      ) as string | number,
      show: true,
    };
  };
  const add = (field: ModelConfigField | undefined) => { if (field) fields.push(field); };
  add(listField('size', params.sizes, 'sizeDefault'));
  add(listField('quality', params.qualities, 'qualityDefault'));
  add(listField('style', params.styles, 'styleDefault'));
  add(listField('aspectRatio', params.aspectRatios, 'aspectRatioDefault'));
  add(listField('resolution', params.resolutions, 'resolutionDefault'));
  const durations = Array.isArray(params.durations)
    ? params.durations.filter((item): item is number => Number.isFinite(Number(item))).map(Number)
    : [];
  if (durations.length) {
    fields.push({
      key: 'duration',
      label: 'duration',
      type: 'enum',
      options: durations,
      default: asNumber(params.durationDefault) ?? durations[0],
      show: true,
    });
  }
  return fields;
};

const mergeField = (base: ModelConfigField, legacy: ModelConfigField): ModelConfigField => ({
  ...base,
  type: legacy.type === 'enum' && (base.type === 'string' || base.type === 'enum') ? 'enum' : base.type,
  options: base.options ?? legacy.options,
  min: base.min ?? legacy.min,
  max: base.max ?? legacy.max,
  default: normalizeEnumDefault(base.default ?? legacy.default, base.options ?? legacy.options) as ModelConfigValue,
  label: base.label || legacy.label,
});

export function buildModelCatalog(models: unknown[]): ModelConfigModel[] {
  return models.flatMap((raw) => {
    const item = asRecord(raw);
    const value = typeof item?.value === 'string' ? item.value.trim() : '';
    if (!item || !value) return [];
    const params = parseParams(item.params);
    const explicitRaw = asRecord(params.spec)?.request;
    const explicit = Array.isArray(explicitRaw)
      ? explicitRaw.map((field) => normalizeField(asRecord(field) ?? {})).filter((field): field is ModelConfigField => Boolean(field))
      : [];
    const byKey = new Map(explicit.map((field) => [field.key, field]));
    for (const legacy of legacyFields(params)) {
      const current = byKey.get(legacy.key);
      byKey.set(legacy.key, current ? mergeField(current, legacy) : legacy);
    }
    const fields = [...byKey.values()];
    const model: ModelConfigModel = {
      value,
      label: typeof item.label === 'string' && item.label.trim() ? item.label.trim() : value,
      isDefault: item.default === 'true' || item.default === true,
      fields,
      defaults: {},
    };
    model.defaults = defaultsForModel(model);
    return [model];
  });
}

const defaultForField = (field: ModelConfigField): ModelConfigValue | undefined => {
  if (field.default !== undefined) return field.default;
  if (field.type === 'enum' && field.options?.length) return field.options[0];
  if (field.type === 'bool') return false;
  if (field.type === 'range' || field.type === 'number') {
    if (typeof field.min === 'number') return field.min;
    return undefined;
  }
  if (field.type === 'object' && field.fields?.length) {
    const nested = Object.fromEntries(
      field.fields
        .map((child) => [child.key, defaultForField(child)] as const)
        .filter((entry): entry is readonly [string, ModelConfigValue] => entry[1] !== undefined),
    );
    return Object.keys(nested).length ? nested : undefined;
  }
  return undefined;
};

export function defaultsForModel(model: ModelConfigModel): Record<string, ModelConfigValue> {
  return Object.fromEntries(
    model.fields
      .map((field) => [field.key, defaultForField(field)] as const)
      .filter((entry): entry is readonly [string, ModelConfigValue] => entry[1] !== undefined),
  );
}

const validateField = (
  field: ModelConfigField,
  value: unknown,
  path: string,
): ModelConfigValue => {
  const fieldPath = path ? `${path}.${field.key}` : field.key;
  if (field.type === 'enum') {
    const options = field.options ?? [];
    if (!options.some((option) => String(option) === String(value))) {
      throw new ModelConfigValidationError('MODEL_PARAM_ENUM', `${fieldPath} 不是允许的选项`, fieldPath);
    }
    return value as ModelConfigValue;
  }
  if (field.type === 'range' || field.type === 'number') {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw new ModelConfigValidationError('MODEL_PARAM_TYPE', `${fieldPath} 必须是数字`, fieldPath);
    }
    if (field.type === 'range' && (
      (field.min !== undefined && value < field.min)
      || (field.max !== undefined && value > field.max)
    )) {
      throw new ModelConfigValidationError('MODEL_PARAM_RANGE', `${fieldPath} 超出范围`, fieldPath);
    }
    return value;
  }
  if (field.type === 'bool') {
    if (typeof value !== 'boolean') {
      throw new ModelConfigValidationError('MODEL_PARAM_TYPE', `${fieldPath} 必须是布尔值`, fieldPath);
    }
    return value;
  }
  if (field.type === 'string') {
    if (typeof value !== 'string') {
      throw new ModelConfigValidationError('MODEL_PARAM_TYPE', `${fieldPath} 必须是字符串`, fieldPath);
    }
    return value;
  }
  if (field.type === 'object') {
    const object = asRecord(value);
    if (!object) {
      throw new ModelConfigValidationError('MODEL_PARAM_TYPE', `${fieldPath} 必须是对象`, fieldPath);
    }
    return validateModelParams(field.fields ?? [], object, fieldPath);
  }
  if (field.type === 'array') {
    if (!Array.isArray(value)) {
      throw new ModelConfigValidationError('MODEL_PARAM_TYPE', `${fieldPath} 必须是数组`, fieldPath);
    }
    return value as ModelConfigValue;
  }
  if (field.type === 'arrayofobject') {
    if (!Array.isArray(value)) {
      throw new ModelConfigValidationError('MODEL_PARAM_TYPE', `${fieldPath} 必须是对象数组`, fieldPath);
    }
    return value.map((entry, index) => {
      const object = asRecord(entry);
      if (!object) {
        throw new ModelConfigValidationError('MODEL_PARAM_TYPE', `${fieldPath}[${index}] 必须是对象`, fieldPath);
      }
      return validateModelParams(field.fields ?? [], object, `${fieldPath}[${index}]`);
    });
  }
  throw new ModelConfigValidationError('MODEL_PARAM_TYPE', `${fieldPath} 的类型不受支持`, fieldPath);
};

export function validateModelParams(
  fields: ModelConfigField[],
  values: Record<string, unknown>,
  path = '',
): Record<string, ModelConfigValue> {
  const known = new Set(fields.map((field) => field.key));
  for (const key of Object.keys(values)) {
    if (!known.has(key)) {
      throw new ModelConfigValidationError(
        'MODEL_PARAM_UNKNOWN',
        `${path ? `${path}.` : ''}${key} 不是当前模型支持的参数`,
        path ? `${path}.${key}` : key,
      );
    }
  }
  return Object.fromEntries(fields.flatMap((field) => {
    if (values[field.key] === undefined) return [];
    return [[field.key, validateField(field, values[field.key], path)] as const];
  }));
}

const mergeValues = (
  fields: ModelConfigField[],
  base: Record<string, unknown>,
  override: Record<string, ModelConfigValue>,
): Record<string, ModelConfigValue> => Object.fromEntries(fields.flatMap((field) => {
  const next = override[field.key];
  const current = base[field.key];
  if (next === undefined) return current === undefined ? [] : [[field.key, current as ModelConfigValue] as const];
  if (field.type === 'object') {
    const currentObject = asRecord(current) ?? {};
    const nextObject = asRecord(next) ?? {};
    return [[field.key, { ...currentObject, ...nextObject } as ModelConfigValue] as const];
  }
  return [[field.key, next] as const];
}));

const legacyDataPatch = (values: Record<string, ModelConfigValue>): Record<string, unknown> => {
  const patch: Record<string, unknown> = {
    size: undefined,
    quality: undefined,
    style: undefined,
    aspectRatio: undefined,
    n: undefined,
    duration: undefined,
    resolution: undefined,
  };
  if (values.size !== undefined) patch.size = values.size;
  if (values.quality !== undefined) patch.quality = values.quality;
  if (typeof values.style === 'string') patch.style = values.style;
  if (values.aspectRatio !== undefined) patch.aspectRatio = values.aspectRatio;
  if (values.aspect_ratio !== undefined) patch.aspectRatio = values.aspect_ratio;
  if (values.n !== undefined) patch.n = values.n;
  if (values.duration !== undefined) patch.duration = values.duration;
  if (values.resolution !== undefined) patch.resolution = values.resolution;
  return patch;
};

export function buildModelDataPatch(
  model: ModelConfigModel,
  base: Record<string, unknown>,
  requested: Record<string, unknown>,
): { values: Record<string, ModelConfigValue>; patch: Record<string, unknown> } {
  const validated = validateModelParams(model.fields, requested);
  const values = mergeValues(model.fields, base, validated);
  return {
    values,
    patch: {
      ...legacyDataPatch(values),
      model: model.value,
      gatewayParams: values,
    },
  };
}

export function modelTypeForNode(kind: string | undefined): 'image' | 'video' | undefined {
  return kind ? MODEL_CONFIG_NODE_TYPES[kind] : undefined;
}

export function isModelConfigNode(node: CanvasNode): boolean {
  return Boolean(modelTypeForNode(node.data.kind));
}
