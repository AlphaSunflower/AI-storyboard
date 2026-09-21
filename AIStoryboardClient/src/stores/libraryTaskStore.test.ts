import { beforeEach, describe, expect, it } from 'vitest';
import { useLibraryTaskStore } from './libraryTaskStore';

describe('libraryTaskStore', () => {
  beforeEach(() => {
    useLibraryTaskStore.setState({ tasks: [], panelOpen: false });
  });

  it('tracks a background AI analysis task as processing', () => {
    useLibraryTaskStore.getState().addAnalysisTask('asset-1', '场景参考', 'AI_GENERATING');

    expect(useLibraryTaskStore.getState().tasks[0]).toMatchObject({
      id: 'analysis:asset-1',
      kind: 'analysis',
      status: 'processing',
      assetId: 'asset-1',
    });
  });

  it('opens the task panel when new work is queued', () => {
    useLibraryTaskStore.getState().upsertTask({
      id: 'import:1',
      kind: 'import',
      label: '角色参考.png',
      status: 'queued',
      progress: 0,
    });

    expect(useLibraryTaskStore.getState().panelOpen).toBe(true);
  });

  it('opens the task panel when analysis starts', () => {
    useLibraryTaskStore.getState().addAnalysisTask('asset-1', '场景参考', 'AI_GENERATING');

    expect(useLibraryTaskStore.getState().panelOpen).toBe(true);
  });

  it('removes finished tasks without dropping active work', () => {
    const store = useLibraryTaskStore.getState();
    store.upsertTask({ id: 'upload:1', kind: 'upload', label: 'a.png', status: 'completed', progress: 100 });
    store.upsertTask({ id: 'upload:2', kind: 'upload', label: 'b.png', status: 'uploading', progress: 50 });

    useLibraryTaskStore.getState().clearFinished();

    expect(useLibraryTaskStore.getState().tasks.map((task) => task.id)).toEqual(['upload:2']);
  });
});
