package io.agentscope.core.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaUtilsConcurrencyTest {

    @Test
    void serializesConcurrentSchemaGeneration() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                futures.add(executor.submit(() ->
                        JsonSchemaUtils.generateSchemaFromClass(SchemaSample.class)));
            }
            for (Future<Map<String, Object>> future : futures) {
                assertThat(future.get())
                        .containsEntry("type", "object")
                        .containsKey("properties");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private record SchemaSample(String name, int count) {
    }
}
