import { beforeEach, describe, expect, it } from 'vitest';
import { useCanvasStore } from './canvasStore';

const reset = () => useCanvasStore.setState({
  projectId: 'project-1',
  canvasId: 'canvas-1',
  nodes: [],
  edges: [],
  generationRecords: [],
  executions: [],
  past: [],
  future: [],
  selectedNodeId: null,
  dragStartSnapshot: null,
  revision: 0,
});

describe('canvas group store actions', () => {
  beforeEach(reset);

  it('groups selected nodes and writes one shared group id/title', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 320, y: 0 });

    const created = useCanvasStore.getState().groupNodes([a, b]);

    expect(created).toMatchObject({ title: '分组 1' });
    const nodes = useCanvasStore.getState().nodes;
    expect(nodes.every((node) => node.data.groupId === created?.id)).toBe(true);
    expect(nodes.every((node) => node.data.groupTitle === '分组 1')).toBe(true);
    expect(nodes.every((node) => typeof node.data.groupCreatedAt === 'number')).toBe(true);
  });

  it('refuses to create a group from fewer than two nodes', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });

    expect(useCanvasStore.getState().groupNodes([a])).toBeNull();
    expect(useCanvasStore.getState().nodes[0].data.groupId).toBeUndefined();
  });

  it('renames every member and ungroups without deleting nodes', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 320, y: 0 });
    const created = useCanvasStore.getState().groupNodes([a, b])!;

    useCanvasStore.getState().renameGroup(created.id, '  第一场  ');
    expect(useCanvasStore.getState().nodes.every((node) => node.data.groupTitle === '第一场')).toBe(true);

    useCanvasStore.getState().ungroupNodes(created.id);
    const nodes = useCanvasStore.getState().nodes;
    expect(nodes).toHaveLength(2);
    expect(nodes.every((node) => node.data.groupId === undefined)).toBe(true);
    expect(nodes.every((node) => node.data.groupTitle === undefined)).toBe(true);
  });

  it('selects exactly the group members', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 320, y: 0 });
    const c = useCanvasStore.getState().addNode('image', { x: 640, y: 0 });
    const created = useCanvasStore.getState().groupNodes([a, b])!;

    useCanvasStore.getState().selectNodes([a, b]);

    const state = useCanvasStore.getState();
    expect(state.selectedNodeId).toBe(b);
    expect(state.nodes.filter((node) => node.selected).map((node) => node.id)).toEqual([a, b]);
    expect(state.nodes.find((node) => node.id === c)?.selected).toBe(false);
    expect(state.nodes.filter((node) => node.data.groupId === created.id)).toHaveLength(2);
  });

  it('keeps copies out of the original group', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 320, y: 0 });
    useCanvasStore.getState().groupNodes([a, b]);

    const duplicates = useCanvasStore.getState().duplicateNodes([a]);
    const copy = useCanvasStore.getState().nodes.find((node) => node.id === duplicates[0]);

    expect(copy).toBeTruthy();
    expect(copy?.data.groupId).toBeUndefined();
    expect(copy?.data.groupTitle).toBeUndefined();
  });

  it('collapses a group, clears its selection and expands again', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 320, y: 0 });
    const created = useCanvasStore.getState().groupNodes([a, b])!;
    useCanvasStore.getState().selectNodes([a, b]);

    useCanvasStore.getState().toggleGroupCollapsed(created.id);

    let state = useCanvasStore.getState();
    expect(state.nodes.every((node) => node.data.groupCollapsed === true)).toBe(true);
    expect(state.nodes.every((node) => node.selected === false)).toBe(true);
    expect(state.selectedNodeId).toBeNull();

    useCanvasStore.getState().toggleGroupCollapsed(created.id, false);
    state = useCanvasStore.getState();
    expect(state.nodes.every((node) => node.data.groupCollapsed === false)).toBe(true);
    expect(state.nodes).toHaveLength(2);
  });

  it('nests a group inside another and refuses cycles', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 0, y: 200 });
    const c = useCanvasStore.getState().addNode('image', { x: 400, y: 0 });
    const d = useCanvasStore.getState().addNode('image', { x: 400, y: 200 });
    const parent = useCanvasStore.getState().groupNodes([a, b])!;
    const child = useCanvasStore.getState().groupNodes([c, d])!;

    expect(useCanvasStore.getState().nestGroup(child.id, parent.id)).toBe(true);
    expect(useCanvasStore.getState().nodes
      .filter((node) => node.data.groupId === child.id)
      .every((node) => node.data.groupParentId === parent.id)).toBe(true);
    // 父分组不能反过来挂到自己的子分组下
    expect(useCanvasStore.getState().nestGroup(parent.id, child.id)).toBe(false);

    expect(useCanvasStore.getState().nestGroup(child.id, null)).toBe(true);
    expect(useCanvasStore.getState().nodes
      .filter((node) => node.data.groupId === child.id)
      .every((node) => node.data.groupParentId === undefined)).toBe(true);
  });

  it('promotes child groups when the parent group is dissolved', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 0, y: 200 });
    const c = useCanvasStore.getState().addNode('image', { x: 400, y: 0 });
    const d = useCanvasStore.getState().addNode('image', { x: 400, y: 200 });
    const parent = useCanvasStore.getState().groupNodes([a, b])!;
    const child = useCanvasStore.getState().groupNodes([c, d])!;
    useCanvasStore.getState().nestGroup(child.id, parent.id);

    useCanvasStore.getState().ungroupNodes(parent.id);

    expect(useCanvasStore.getState().nodes
      .filter((node) => node.data.groupId === child.id)
      .every((node) => node.data.groupParentId === undefined)).toBe(true);
    expect(useCanvasStore.getState().nodes.filter((node) => node.data.groupId === parent.id)).toHaveLength(0);
  });

  it('clears dangling parent ids when the last parent node is deleted', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 0, y: 200 });
    const c = useCanvasStore.getState().addNode('image', { x: 400, y: 0 });
    const d = useCanvasStore.getState().addNode('image', { x: 400, y: 200 });
    const parent = useCanvasStore.getState().groupNodes([a, b])!;
    const child = useCanvasStore.getState().groupNodes([c, d])!;
    useCanvasStore.getState().nestGroup(child.id, parent.id);

    useCanvasStore.getState().deleteNodes([a, b]);

    expect(useCanvasStore.getState().nodes
      .filter((node) => node.data.groupId === child.id)
      .every((node) => node.data.groupParentId === undefined)).toBe(true);
  });

  it('restores grouping through undo', () => {
    const a = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const b = useCanvasStore.getState().addNode('image', { x: 320, y: 0 });
    useCanvasStore.getState().groupNodes([a, b]);
    expect(useCanvasStore.getState().nodes.every((node) => Boolean(node.data.groupId))).toBe(true);

    useCanvasStore.getState().undo();

    expect(useCanvasStore.getState().nodes.every((node) => node.data.groupId === undefined)).toBe(true);
  });
});
