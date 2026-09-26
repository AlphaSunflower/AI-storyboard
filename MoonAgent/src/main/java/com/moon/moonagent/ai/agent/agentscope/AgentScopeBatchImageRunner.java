package com.moon.moonagent.ai.agent.agentscope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

final class AgentScopeBatchImageRunner {

    private AgentScopeBatchImageRunner() {
    }

    record Result(List<AgentScopeBatchImageSlots.Slot> slots, boolean cancelled) {
    }

    static Result run(
            ExecutorService executor,
            List<AgentScopeBatchImageSlots.Slot> initial,
            int concurrency,
            BooleanSupplier cancelRequested,
            Function<AgentScopeBatchImageSlots.Slot, AgentScopeBatchImageSlots.Slot> operation,
            Consumer<AgentScopeBatchImageSlots.Slot> onFinished) {
        List<AgentScopeBatchImageSlots.Slot> slots = initial == null ? List.of() : initial.stream()
                .sorted(Comparator.comparingInt(AgentScopeBatchImageSlots.Slot::index))
                .toList();
        if (slots.isEmpty()) return new Result(List.of(), false);

        ConcurrentLinkedQueue<AgentScopeBatchImageSlots.Slot> pending =
                new ConcurrentLinkedQueue<>(slots);
        BlockingQueue<Object> finished = new LinkedBlockingQueue<>();
        Object done = new Object();
        int workerCount = Math.max(1, Math.min(concurrency, slots.size()));

        for (int worker = 0; worker < workerCount; worker++) {
            executor.submit(() -> {
                try {
                    while (!cancelRequested.getAsBoolean()) {
                        AgentScopeBatchImageSlots.Slot slot = pending.poll();
                        if (slot == null) return;
                        AgentScopeBatchImageSlots.Slot result;
                        try {
                            result = operation.apply(slot.running());
                            if (result == null) {
                                result = slot.fail("图片生成未返回结果", java.time.OffsetDateTime.now());
                            }
                        } catch (Exception e) {
                            result = slot.fail(
                                    e.getMessage() == null ? "图片生成失败" : e.getMessage(),
                                    java.time.OffsetDateTime.now());
                        }
                        finished.offer(result);
                    }
                } finally {
                    finished.offer(done);
                }
            });
        }

        Map<Integer, AgentScopeBatchImageSlots.Slot> outcomes = new ConcurrentHashMap<>();
        boolean cancelled = false;
        int workersDone = 0;
        try {
            while (workersDone < workerCount) {
                Object item = finished.poll(100, TimeUnit.MILLISECONDS);
                if (item == null) {
                    if (!cancelled && cancelRequested.getAsBoolean()) {
                        cancelled = true;
                        pending.clear();
                    }
                    continue;
                }
                if (item == done) {
                    workersDone++;
                    continue;
                }
                AgentScopeBatchImageSlots.Slot outcome = (AgentScopeBatchImageSlots.Slot) item;
                outcomes.put(outcome.index(), outcome);
                onFinished.accept(outcome);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
            pending.clear();
        }
        if (cancelRequested.getAsBoolean()) cancelled = true;

        List<AgentScopeBatchImageSlots.Slot> merged = new ArrayList<>();
        for (AgentScopeBatchImageSlots.Slot slot : slots) {
            AgentScopeBatchImageSlots.Slot outcome = outcomes.get(slot.index());
            if (outcome != null) {
                merged.add(outcome);
            } else if (cancelled) {
                merged.add(slot.cancel());
            } else {
                merged.add(slot);
            }
        }
        return new Result(List.copyOf(merged), cancelled);
    }
}
