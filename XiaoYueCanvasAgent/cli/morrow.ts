#!/usr/bin/env node
import { randomUUID } from 'node:crypto';
import { BRIDGE_DEFAULT_PORT, type CanvasBridgeResponse } from '../src/canvas/bridgeProtocol.ts';

const bridgeUrl = (process.env.MORROW_BRIDGE_URL ?? `http://127.0.0.1:${BRIDGE_DEFAULT_PORT}`).replace(/\/$/, '');
const bridgeToken = process.env.MORROW_BRIDGE_TOKEN?.trim() ?? '';

const USAGE = `morrow — 通过 Bridge 读写浏览器中打开的 Morrow 画布

用法：
  morrow canvas projects
  morrow canvas summary [--project <id>] [--json]
  morrow canvas nodes list [--project <id>] [--json]
  morrow canvas node create_text [--project <id>] --label <文字> [--position x,y] [--json]
  morrow canvas node create_image [--project <id>] --label <文字> [--position x,y] [--json]
  morrow canvas node delete --node <id> [--project <id>] [--json]
  morrow canvas layout dag [--project <id>] [--json]
  morrow canvas layout grid [--project <id>] [--json]
  morrow canvas undo [--project <id>] [--json]
  morrow canvas redo [--project <id>] [--json]

全局选项：
  --project <id>   目标项目；省略时使用当前唯一打开的项目
  --canvas <id>    目标画布；同一项目多个画布时必须指定
  --json           以 JSON 输出完整响应
  --dry-run        只预览变更，不真正执行

环境变量：
  MORROW_BRIDGE_URL   Bridge 地址（默认 http://127.0.0.1:${BRIDGE_DEFAULT_PORT}）
  MORROW_BRIDGE_TOKEN Bridge 访问令牌（必填）
`;

interface ParsedArgs {
  command: string[];
  options: Record<string, string | boolean>;
}

function parseArgs(argv: string[]): ParsedArgs {
  const command: string[] = [];
  const options: Record<string, string | boolean> = {};
  let i = 0;
  while (i < argv.length) {
    const arg = argv[i];
    if (arg.startsWith('--')) {
      const eq = arg.indexOf('=');
      if (eq >= 0) {
        options[arg.slice(2, eq)] = arg.slice(eq + 1);
      } else {
        const next = argv[i + 1];
        if (next !== undefined && !next.startsWith('--')) { options[arg.slice(2)] = next; i += 1; }
        else options[arg.slice(2)] = true;
      }
    } else {
      command.push(arg);
    }
    i += 1;
  }
  return { command, options };
}

const parsePosition = (value: unknown): { x: number; y: number } | undefined => {
  if (typeof value !== 'string') return undefined;
  const [x, y] = value.split(',').map((part) => Number(part.trim()));
  if (!Number.isFinite(x) || !Number.isFinite(y)) return undefined;
  return { x, y };
};

interface CommandRequest {
  projectId?: string;
  canvasId?: string;
  method: string;
  params: Record<string, unknown>;
  dryRun?: boolean;
}

function buildRequest(sub: string, o: Record<string, string | boolean>): CommandRequest | null {
  const projectId = typeof o.project === 'string' && o.project ? o.project : undefined;
  const canvasId = typeof o.canvas === 'string' && o.canvas ? o.canvas : undefined;
  const dryRun = o['dry-run'] === true || o['dry-run'] === 'true';
  const label = typeof o.label === 'string' ? o.label : undefined;
  const position = parsePosition(o.position);

  switch (sub) {
    case 'summary':
      return { projectId, canvasId, method: 'canvas.get_summary', params: {}, dryRun };
    case 'nodes list':
      return { projectId, canvasId, method: 'canvas.list_nodes', params: {}, dryRun };
    case 'node create_text':
      return { projectId, canvasId, method: 'canvas.node.create_text', params: { label: label ?? '文字', position }, dryRun };
    case 'node create_image':
      return { projectId, canvasId, method: 'canvas.node.create_image_placeholder', params: { label: label ?? '图片', position }, dryRun };
    case 'node delete':
      return { projectId, canvasId, method: 'canvas.node.delete', params: { nodeIds: typeof o.node === 'string' && o.node ? [o.node] : [] }, dryRun };
    case 'layout dag':
      return { projectId, canvasId, method: 'canvas.layout.dag', params: { direction: typeof o.direction === 'string' ? o.direction : undefined }, dryRun };
    case 'layout grid':
      return { projectId, canvasId, method: 'canvas.layout.grid', params: {}, dryRun };
    case 'undo':
      return { projectId, canvasId, method: 'canvas.undo', params: {}, dryRun };
    case 'redo':
      return { projectId, canvasId, method: 'canvas.redo', params: {}, dryRun };
    default:
      return null;
  }
}

async function getProjects(): Promise<string[]> {
  const res = await fetch(`${bridgeUrl}/projects`, { headers: bridgeHeaders() });
  if (!res.ok) throw new Error(`无法连接 Bridge（HTTP ${res.status}）`);
  return (await res.json()) as string[];
}

async function sendCommand(request: CommandRequest): Promise<CanvasBridgeResponse> {
  const res = await fetch(`${bridgeUrl}/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...bridgeHeaders() },
    body: JSON.stringify({ version: 1, id: randomUUID(), source: 'cli', ...request }),
  });
  if (!res.ok) throw new Error(`Bridge 返回 HTTP ${res.status}`);
  return (await res.json()) as CanvasBridgeResponse;
}

function bridgeHeaders(): Record<string, string> {
  return bridgeToken ? { 'X-Bridge-Token': bridgeToken } : {};
}

function printResponse(response: CanvasBridgeResponse, json: boolean) {
  if (json) {
    console.log(JSON.stringify(response, null, 2));
    return;
  }
  if (!response.ok) {
    console.error(`✗ ${response.error?.code ?? 'ERROR'}: ${response.error?.message ?? '未知错误'}`);
    return;
  }
  console.log(JSON.stringify(response.result, null, 2));
}

async function main() {
  const { command, options } = parseArgs(process.argv.slice(2));
  const json = options.json === true || options.json === 'true';

  if (command.length === 0 || command[0] === 'help' || command[0] === '--help') {
    console.log(USAGE);
    return;
  }

  if (command[0] !== 'canvas') {
    console.error('✗ 未知命令，用 --help 查看用法');
    process.exitCode = 1;
    return;
  }

  if (!bridgeToken) {
    console.error('✗ 未设置 MORROW_BRIDGE_TOKEN，无法连接 Bridge');
    process.exitCode = 1;
    return;
  }

  const sub = command.slice(1).join(' ');

  if (sub === 'projects') {
    try {
      const projects = await getProjects();
      if (json) console.log(JSON.stringify(projects, null, 2));
      else if (!projects.length) console.log('（没有打开的项目）');
      else projects.forEach((p) => console.log(p));
    } catch (error) {
      console.error(`✗ ${error instanceof Error ? error.message : '查询失败'}`);
      process.exitCode = 1;
    }
    return;
  }

  const request = buildRequest(sub, options);
  if (!request) {
    console.error('✗ 未知命令，用 --help 查看用法');
    process.exitCode = 1;
    return;
  }

  try {
    const response = await sendCommand(request);
    printResponse(response, json);
    if (!response.ok) process.exitCode = 1;
  } catch (error) {
    console.error(`✗ ${error instanceof Error ? error.message : '命令失败'}`);
    console.error('  提示：请确认 Bridge 已启动（npm run bridge）且浏览器已打开目标项目。');
    process.exitCode = 1;
  }
}

void main();
