package com.moon.moonagent.ai.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型目录「版本号失效」回归：网关任何一次目录变更都会换 {@code gateway:models:version}，
 * 消费端下一次请求就必须重拉，而不是干等 60s TTL。
 *
 * <p>不用 Mockito：本机沙箱里 Mockito 无法 self-attach（ByteBuddy 起不来外部 attach 进程），
 * 用内存 Redis 替身 + 真 HttpServer 才能把这条链路跑起来。
 */
class GatewayModelServiceImplCatalogVersionTest {

    private static final String VERSION_KEY = "gateway:models:version";

    private final Map<String, String> redisStore = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private String body = "";

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> {
            requests.incrementAndGet();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    private GatewayModelServiceImpl service(boolean withRedis) {
        AiConfigProperties config = new AiConfigProperties(new AiConfigProperties.Gateway());
        config.getGateway().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        config.getGateway().setApiKey("lg-test");
        StringRedisTemplate template = withRedis ? inMemoryRedis(redisStore) : null;
        return new GatewayModelServiceImpl(config, provider(template));
    }

    private static String listBody(String model, String version) {
        return """
                {"object":"list","version":"%s","data":[
                  {"id":"%s","is_default":true,"params":{"sizes":["1024x1024"]}}
                ]}""".formatted(version, model);
    }

    @Test
    void unchangedVersionServesFromLocalCache() throws Exception {
        startServer();
        body = listBody("model-a", "v1");
        redisStore.put(VERSION_KEY, "v1");
        GatewayModelServiceImpl service = service(true);

        assertThat(service.fetchModels("image")).hasSize(1);
        assertThat(service.fetchModels("image")).hasSize(1);

        assertThat(requests.get()).as("版本号没变就不该重复请求网关").isEqualTo(1);
    }

    @Test
    void versionChangeForcesImmediateRefetchWithoutWaitingForTtl() throws Exception {
        startServer();
        body = listBody("model-a", "v1");
        redisStore.put(VERSION_KEY, "v1");
        GatewayModelServiceImpl service = service(true);
        assertThat(service.fetchModels("image").getFirst()).containsEntry("value", "model-a");

        // admin 切换默认模型：网关换版本号 + 列表内容变化
        body = listBody("model-b", "v2");
        redisStore.put(VERSION_KEY, "v2");

        assertThat(service.fetchModels("image").getFirst())
                .as("版本号一变必须立刻拿到新默认，而不是等 60s TTL")
                .containsEntry("value", "model-b")
                .containsEntry("default", "true");
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void withoutRedisTheCacheFallsBackToTtlInsteadOfHammeringTheGateway() throws Exception {
        startServer();
        body = listBody("model-a", "v1");
        GatewayModelServiceImpl service = service(false);

        service.fetchModels("image");
        service.fetchModels("image");

        assertThat(requests.get()).as("没有 Redis 时退回 TTL 缓存，不能每次请求都打网关").isEqualTo(1);
        assertThat(service.catalogVersion()).as("仍可从响应体里取到版本号").isEqualTo("v1");
    }

    /** 内存 Redis 替身：只支持缓存组件用到的 opsForValue().get/set */
    @SuppressWarnings("unchecked")
    private static StringRedisTemplate inMemoryRedis(Map<String, String> store) {
        return new StringRedisTemplate() {
            private final ValueOperations<String, String> values =
                    (ValueOperations<String, String>) Proxy.newProxyInstance(
                            GatewayModelServiceImplCatalogVersionTest.class.getClassLoader(),
                            new Class<?>[]{ValueOperations.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "get" -> store.get(String.valueOf(args[0]));
                                case "set" -> {
                                    store.put(String.valueOf(args[0]), String.valueOf(args[1]));
                                    yield null;
                                }
                                case "toString" -> "InMemoryValueOperations";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> throw new UnsupportedOperationException(method.getName());
                            });

            @Override
            public ValueOperations<String, String> opsForValue() {
                return values;
            }
        };
    }

    /** ObjectProvider 替身：template 为 null 时表示「未装配 Redis」 */
    private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate template) {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                return template;
            }

            @Override
            public StringRedisTemplate getObject(Object... args) {
                return template;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return template;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return template;
            }

            @Override
            public Iterator<StringRedisTemplate> iterator() {
                return (template == null ? List.<StringRedisTemplate>of() : List.of(template)).iterator();
            }
        };
    }
}
