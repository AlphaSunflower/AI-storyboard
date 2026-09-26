package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeBatchImageRunnerTest {

    @Test
    void neverRunsMoreThanConfiguredWorkers() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        List<AgentScopeBatchImageSlots.Slot> slots = IntStream.range(0, 6)
                .mapToObj(index -> AgentScopeBatchImageSlots.Slot.pending(index, "prompt-" + index))
                .toList();
        try {
            var result = AgentScopeBatchImageRunner.run(
                    executor,
                    slots,
                    2,
                    () -> false,
                    slot -> {
                        int active = inFlight.incrementAndGet();
                        maxInFlight.accumulateAndGet(active, Math::max);
                        try {
                            Thread.sleep(30);
                            return slot.complete("/" + slot.index() + ".png", "asset-" + slot.index(),
                                    OffsetDateTime.now());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return slot.fail("interrupted", OffsetDateTime.now());
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    },
                    ignored -> {
                    });

            assertThat(result.cancelled()).isFalse();
            assertThat(maxInFlight.get()).isEqualTo(2);
            assertThat(result.slots()).allMatch(AgentScopeBatchImageSlots.Slot::completed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void emitsCompletedSlotsInCompletionOrder() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        List<Integer> callbackOrder = new CopyOnWriteArrayList<>();
        List<AgentScopeBatchImageSlots.Slot> slots = List.of(
                AgentScopeBatchImageSlots.Slot.pending(0, "slow"),
                AgentScopeBatchImageSlots.Slot.pending(1, "fast"));
        try {
            var future = executor.submit(() -> AgentScopeBatchImageRunner.run(
                    executor,
                    slots,
                    2,
                    () -> false,
                    slot -> {
                        if (slot.index() == 0) {
                            firstStarted.countDown();
                            try {
                                releaseFirst.await(2, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return slot.complete("/" + slot.index() + ".png", "asset-" + slot.index(),
                                OffsetDateTime.now());
                    },
                    completed -> callbackOrder.add(completed.index())));

            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            waitFor(() -> callbackOrder.contains(1));
            releaseFirst.countDown();
            future.get(2, TimeUnit.SECONDS);

            assertThat(callbackOrder).containsExactly(1, 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationStopsUnstartedSlots() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicBoolean cancel = new AtomicBoolean();
        List<Integer> started = new CopyOnWriteArrayList<>();
        List<AgentScopeBatchImageSlots.Slot> slots = List.of(
                AgentScopeBatchImageSlots.Slot.pending(0, "first"),
                AgentScopeBatchImageSlots.Slot.pending(1, "second"));
        try {
            var result = AgentScopeBatchImageRunner.run(
                    executor,
                    slots,
                    1,
                    cancel::get,
                    slot -> {
                        started.add(slot.index());
                        cancel.set(true);
                        return slot.complete("/first.png", "asset-1", OffsetDateTime.now());
                    },
                    ignored -> {
                    });

            assertThat(result.cancelled()).isTrue();
            assertThat(started).containsExactly(0);
            assertThat(result.slots().get(1).status()).isEqualTo("cancelled");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
    }
}
