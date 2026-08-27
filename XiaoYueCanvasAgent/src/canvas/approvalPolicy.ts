// 工具的三级权限行为：
//   auto       —— 直接执行，不预览
//   confirm    —— 先生成 preview，等用户确认后再执行
//   forceDryRun —— 即使级别是 auto，当参数规模超过阈值时也强制预览
export type ApprovalLevel = 'auto' | 'confirm';

export interface ApprovalPolicy {
  level: ApprovalLevel;
  forceDryRun?: (args: Record<string, unknown>) => boolean;
}

export const APPROVAL_THRESHOLDS = {
  deleteNodes: 3,
  deleteEdges: 3,
  layoutNodes: 20,
  moveNodes: 20,
  resizeNodes: 20,
} as const;

const argArrayLength = (args: Record<string, unknown>) => {
  for (const key of ['nodeIds', 'edgeIds', 'positions', 'sizes', 'items']) {
    const value = args[key];
    if (Array.isArray(value)) return value.length;
  }
  return 0;
};

export const dryRunWhenExceeds = (threshold: number) => (args: Record<string, unknown>) => argArrayLength(args) > threshold;

export const AUTO: ApprovalPolicy = { level: 'auto' };
export const CONFIRM: ApprovalPolicy = { level: 'confirm' };
export const autoWithDryRun = (threshold: number): ApprovalPolicy => ({ level: 'auto', forceDryRun: dryRunWhenExceeds(threshold) });
