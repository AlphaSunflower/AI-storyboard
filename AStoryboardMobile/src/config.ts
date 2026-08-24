/**
 * 移动端配置。
 */

import { Platform } from 'react-native';
import Constants from 'expo-constants';

/**
 * 移动端后端地址。
 *
 * 开发期（expo start / Expo Go / dev client）@expo/cli 会注入 hostUri，
 * 即包管理器所在主机（真机 = 开发机局域网 IP，如 "192.168.100.61:8081"）。
 * 从它取主机名即可自动指向开发机——真机无需再手动填 IP，
 * Android 模拟器 hostUri=10.0.2.2、iOS 模拟器=localhost 也各自正确。
 * 仅独立安装包（无 hostUri）才回退到平台默认回环地址。
 *
 * ⚠ 统一走 API Gateway (8080)：前端一律经网关白名单/验签后分发到下游服务
 *   （/api/agent/**→moon-agent，其余→ai-storyboard），见 spring-cloud-microservice-architecture。
 *   不要直连 8082 后端——绕过网关会丢了 /api/agent/** 等网关路由，且违背统一拦截。
 * ⚠ 切勿再硬编码 10.0.2.2 / localhost 作为真机地址：两者在真机上指向手机自身，
 *   请求到不了开发机，后端零日志。
 */
const metroHost = Constants.expoConfig?.hostUri?.split(':')[0] ?? null;

const host = metroHost ?? (Platform.OS === 'android' ? '10.0.2.2' : 'localhost');

export const BACKEND_URL: string = `http://${host}:8080`;

export function assetUrl(path: string | null): string {
  if (!path) return '';
  if (path.startsWith('http') || path.startsWith('data:')) return path;
  return BACKEND_URL + path;
}

export interface ModelOption {
  value: string;
  label: string;
  params?: Record<string, unknown> | null;
}

export const IMAGE_MODELS = [
  { value: 'gpt-image-2', label: 'GPT Image 2' },
  { value: 'gemini-3-pro-image-preview', label: 'Gemini 3 Pro Image' },
  { value: 'dall-e-3', label: 'DALL·E 3' },
  { value: 'sdxl', label: 'Stable Diffusion XL' },
  { value: 'midjourney-v6', label: 'Midjourney V6' },
  { value: 'flux-pro', label: 'FLUX Pro' },
] as const;

export const VIDEO_MODELS = [
  { value: 'MiniMax-H3', label: 'MiniMax H3' },
] as const;

export const UNDERSTANDING_MODELS = [
  { value: 'gemini-3-flash-preview', label: 'Gemini 3 Flash' },
] as const;

export const DEFAULT_IMAGE_MODEL: string = IMAGE_MODELS[0].value;
export const DEFAULT_VIDEO_MODEL: string = VIDEO_MODELS[0].value;
export const DEFAULT_UNDERSTANDING_MODEL: string = UNDERSTANDING_MODELS[0].value;

export const DEFAULT_IMAGE_SIZE = '1024x1024';
export const DEFAULT_IMAGE_QUALITY = 'auto';
export const DEFAULT_VIDEO_PRESET = '8s-16:9';
export const VIDEO_POLL_INTERVAL = 5000;
