package com.llmgateway.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 测试用内存 Redis：不依赖 Mockito。
 *
 * <p>为什么不用 mock：本机沙箱里 Mockito 无法 self-attach（ByteBuddy 起不来外部 attach 进程，
 * {@code Could not initialize plugin: interface org.mockito.plugins.MockMaker}），
 * 用 mock 写的用例在本地一条都跑不起来；缓存失效这类"读了写、写了删"的逻辑用内存替身反而更直观。
 *
 * <p>只实现缓存组件真正用到的方法：{@code opsForValue().get/set}、{@code delete}、{@code keys}。
 */
public class InMemoryStringRedisTemplate extends StringRedisTemplate {

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final List<String> deletedKeys = new ArrayList<>();
    private volatile boolean failReads;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values =
            (ValueOperations<String, String>) Proxy.newProxyInstance(
                    InMemoryStringRedisTemplate.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> {
                            if (failReads) throw new IllegalStateException("redis unavailable");
                            yield store.get(String.valueOf(args[0]));
                        }
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

    @Override
    public Boolean delete(String key) {
        deletedKeys.add(key);
        return store.remove(key) != null;
    }

    @Override
    public Set<String> keys(String pattern) {
        Pattern regex = Pattern.compile(pattern.replace("*", ".*"));
        Set<String> matched = new HashSet<>();
        for (String key : store.keySet()) {
            if (regex.matcher(key).matches()) matched.add(key);
        }
        return matched;
    }

    /** 当前是否让读操作抛异常（模拟 Redis 不可用） */
    public void failReads(boolean fail) {
        this.failReads = fail;
    }

    /** 绕过缓存组件直接读底层值（断言用） */
    public String rawGet(String key) {
        return store.get(key);
    }

    /** 直接写入底层值（模拟别的进程/脚本改过 Redis） */
    public void rawSet(String key, String value) {
        store.put(key, value);
    }

    /** 被删除过的 key 序列（含重复，按调用顺序） */
    public List<String> deletedKeys() {
        return List.copyOf(deletedKeys);
    }
}
