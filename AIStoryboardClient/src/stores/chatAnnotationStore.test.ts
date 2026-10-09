import { beforeEach, describe, expect, it } from 'vitest';
import { selectAnnotationsForMessage, useChatAnnotationStore } from './chatAnnotationStore';

describe('chatAnnotationStore', () => {
  beforeEach(() => {
    useChatAnnotationStore.getState().reset();
  });

  it('adds annotations and groups them by message', () => {
    const store = useChatAnnotationStore.getState();
    store.addAnnotation({ conversationId: 'c1', messageId: 'm1', quote: 'A' });
    store.addAnnotation({ conversationId: 'c1', messageId: 'm1', quote: 'B', comment: '   ' });
    store.addAnnotation({ conversationId: 'c1', messageId: 'm2', quote: 'C' });

    const all = useChatAnnotationStore.getState().annotations;
    expect(selectAnnotationsForMessage(all, 'm1').map((annotation) => annotation.quote)).toEqual(['A', 'B']);
    expect(selectAnnotationsForMessage(all, 'm2')).toHaveLength(1);
    expect(selectAnnotationsForMessage(all, 'missing')).toEqual([]);
  });

  it('ignores blank quotes', () => {
    expect(useChatAnnotationStore.getState()
      .addAnnotation({ conversationId: 'c1', messageId: 'm1', quote: '   ' })).toBeNull();
    expect(useChatAnnotationStore.getState().annotations).toHaveLength(0);
  });

  it('updates and removes a comment', () => {
    const created = useChatAnnotationStore.getState()
      .addAnnotation({ conversationId: 'c1', messageId: 'm1', quote: 'A' })!;
    useChatAnnotationStore.getState().updateComment(created.id, '  看看这里 ');
    expect(useChatAnnotationStore.getState().annotations[0].comment).toBe('看看这里');

    useChatAnnotationStore.getState().removeAnnotation(created.id);
    expect(useChatAnnotationStore.getState().annotations).toHaveLength(0);
  });

  it('removes every annotation of one message', () => {
    const store = useChatAnnotationStore.getState();
    store.addAnnotation({ conversationId: 'c1', messageId: 'm1', quote: 'A' });
    useChatAnnotationStore.getState().addAnnotation({ conversationId: 'c1', messageId: 'm2', quote: 'B' });

    useChatAnnotationStore.getState().removeForMessage('m1');
    expect(useChatAnnotationStore.getState().annotations.map((annotation) => annotation.messageId)).toEqual(['m2']);
  });

  it('consumes one conversation and keeps the others', () => {
    const store = useChatAnnotationStore.getState();
    store.addAnnotation({ conversationId: 'c1', messageId: 'm1', quote: 'A' });
    useChatAnnotationStore.getState().addAnnotation({ conversationId: 'c2', messageId: 'm2', quote: 'B' });

    const consumed = useChatAnnotationStore.getState().consumeConversation('c1');
    expect(consumed.map((annotation) => annotation.quote)).toEqual(['A']);
    expect(useChatAnnotationStore.getState().annotations.map((annotation) => annotation.conversationId)).toEqual(['c2']);
  });
});
