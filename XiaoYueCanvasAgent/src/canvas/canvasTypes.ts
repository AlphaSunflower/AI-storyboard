import type { XYPosition } from '@xyflow/react';
import type { MediaKind } from '../types';

export type CommandSource = 'user' | 'agent' | 'mcp' | 'cli';

export interface CanvasCommandContext {
  projectId: string;
  source: CommandSource;
  requestId?: string;
  expectedRevision?: number;
  dryRun?: boolean;
  approvalToken?: string;
  signal?: AbortSignal;
}

export interface CanvasChangeSummary {
  createdNodeIds?: string[];
  deletedNodeIds?: string[];
  movedNodeIds?: string[];
  resizedNodeIds?: string[];
  createdEdgeIds?: string[];
  deletedEdgeIds?: string[];
  affectedNodeIds?: string[];
  description: string;
}

export interface CanvasCommandError {
  code: string;
  message: string;
  details?: unknown;
}

export interface CanvasCommandResult<T = unknown> {
  ok: boolean;
  status: 'preview' | 'applied' | 'accepted' | 'failed' | 'rejected';
  transactionId?: string;
  revision?: number;
  result?: T;
  changes?: CanvasChangeSummary;
  error?: CanvasCommandError;
}

export interface CanvasNodeSummary {
  id: string;
  kind: MediaKind;
  label: string;
  position: XYPosition;
  size: { width: number; height: number };
  assetId?: string;
  status: string;
  derivedFrom?: string;
  generationRecordIds?: string[];
  generationStatus?: string;
  materializationStatus?: string;
  resultNodeIds?: string[];
  placeholder?: {
    media: 'image' | 'video';
    state: 'empty' | 'filled';
    resultSlot: boolean;
    producerNodeId?: string;
    order?: number;
    edgeId?: string;
  };
  missingInputs?: Array<{
    portId: string;
    label: string;
    media: string;
    required: boolean;
    min: number;
    bound: number;
  }>;
  ports?: {
    inputs: Array<{
      id: string;
      label: string;
      media: string;
      acceptsKinds: string[];
      role: string;
      min: number;
      max: number;
      required: boolean;
      ordered: boolean;
    }>;
    outputs: Array<{
      id: string;
      label: string;
      media: string;
      role: string;
    }>;
  };
  actions?: Array<{
    id: string;
    label: string;
    capabilityId: string;
    resultPolicy: string;
    startable: boolean;
  }>;
  runnable?: Record<string, boolean>;
}
