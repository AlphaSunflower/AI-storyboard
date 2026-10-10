import { create } from 'zustand';

export interface SideChatSelection {
  id: string;
  text: string;
  messageId?: string;
  occurrence?: number;
  createdAt: number;
}

interface SideChatSelectionState {
  selections: SideChatSelection[];
  addSelection: (input: {
    text: string;
    messageId?: string;
    occurrence?: number;
  }) => SideChatSelection | null;
  removeSelection: (id: string) => void;
  replaceSelections: (selections: SideChatSelection[]) => void;
  clear: () => void;
}

let selectionSeq = 0;

function nextSelectionId() {
  selectionSeq += 1;
  return `side-selection-${Date.now()}-${selectionSeq}`;
}

function normalizeSelections(value: unknown): SideChatSelection[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const record = item as Partial<SideChatSelection>;
    const text = typeof record.text === 'string' ? record.text.trim() : '';
    if (!text) return [];
    return [{
      id: typeof record.id === 'string' && record.id ? record.id : nextSelectionId(),
      text,
      messageId: typeof record.messageId === 'string' ? record.messageId : undefined,
      occurrence: typeof record.occurrence === 'number' ? record.occurrence : undefined,
      createdAt: typeof record.createdAt === 'number' ? record.createdAt : Date.now(),
    }];
  });
}

export const useSideChatSelectionStore = create<SideChatSelectionState>((set) => ({
  selections: [],
  addSelection: (input) => {
    const text = input.text.trim();
    if (!text) return null;
    let result: SideChatSelection | null = null;
    set((state) => {
      const duplicate = state.selections.find((selection) =>
        selection.text === text
        && selection.messageId === input.messageId
        && selection.occurrence === input.occurrence);
      if (duplicate) {
        result = duplicate;
        return state;
      }
      const selection: SideChatSelection = {
        id: nextSelectionId(),
        text,
        messageId: input.messageId,
        occurrence: input.occurrence,
        createdAt: Date.now(),
      };
      result = selection;
      return { selections: [...state.selections, selection] };
    });
    return result;
  },
  removeSelection: (id) => set((state) => ({
    selections: state.selections.filter((selection) => selection.id !== id),
  })),
  replaceSelections: (selections) => set({ selections: normalizeSelections(selections) }),
  clear: () => set({ selections: [] }),
}));
