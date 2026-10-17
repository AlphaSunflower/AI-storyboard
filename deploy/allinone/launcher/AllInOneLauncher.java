package moon.allinone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 单 JVM 多服务启动器。
 *
 * <p>把原本 6 个独立容器（= 6 个 JVM）的 Spring Boot 服务放进同一个 JVM：
 * 每个服务一个独立的 {@link URLClassLoader}（父加载器只给到 platform loader），
 * 因此每个服务的 {@code application.yml}、{@code BOOT-INF/lib} 依赖和静态状态互不干扰，
 * 行为和「单独一个容器里跑 java -cp BOOT-INF/classes:BOOT-INF/lib/* 主类」完全一致。
 * 省下的是 6 份 JVM 自身开销（JIT 编译器线程、GC 线程、内部线程栈），2 核 2G 上这是能否跑起来的关键。
 *
 * <p>启动参数：
 * <ul>
 *   <li>公共：{@code --spring.profiles.active}、{@code --logging.pattern.console}（带服务名前缀）</li>
 *   <li>每个服务：{@code --spring.application.json=<config/<服务>.json 展开 env 占位符>}
 *       这个属性源在 Spring Boot 里的优先级高于 application-prod.yml 与环境变量，
 *       因此「共用环境变量、按服务区分数据源/密钥」可以精确表达（见 config/ 目录）。</li>
 * </ul>
 *
 * <p>存活判定：web 服务的 {@code SpringApplication.run()} 在上下文刷新完成后就会返回
 * （main 返回 ≠ 进程结束），所以 main 返回后由「端口看门狗」继续守着；
 * 端口连续探测失败即认为该服务已死，在新的类加载器里重启它；
 * 连续失败超过上限则 halt 整个 JVM，交给 compose 的 {@code restart: unless-stopped} 整体自愈。
 */
public final class AllInOneLauncher {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i)(password|secret|api[-_]?key|token)");
    /** 端口起来最多等多久（2 核机器上 6 个上下文抢 CPU，启动会比较慢） */
    private static final long PORT_UP_TIMEOUT_SECONDS = 420;
    private static final int PORT_PROBE_INTERVAL_SECONDS = 15;
    private static final int PORT_PROBE_FAILURES_BEFORE_RESTART = 4;

    /** JVM 正在关闭（docker stop / halt）：看门狗不要再重启服务 */
    private static volatile boolean stopping = false;

    /** 一个被托管的服务。port 用于存活探测，必须和该服务 application.yml 里的 server.port 一致。 */
    private record ServiceSpec(String name, String mainClass, int port, int delaySeconds) {}

    /**
     * 启动顺序即声明顺序：模型网关 → 业务服务 → 统一入口。
     * 入口网关放最后，其它服务已经在监听端口，避免首个请求打到还没起来的服务。
     */
    private static final List<ServiceSpec> SERVICES = List.of(
            new ServiceSpec("llm-gateway", "com.llmgateway.LLMGatewayApplication", 8083, 0),
            new ServiceSpec("backend", "com.storyboard.StoryboardApplication", 8082, 2),
            new ServiceSpec("asset-service", "com.storyboard.asset.AssetApplication", 8085, 2),
            new ServiceSpec("canvas", "com.moon.xiaoyuecanvas.XiaoYueCanvasApplication", 9090, 2),
            new ServiceSpec("moon-agent", "com.moon.moonagent.MoonAgentApplication", 8084, 2),
            new ServiceSpec("api-gateway", "com.moon.apigateway.ApiGatewayApplication", 8080, 3));

    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopping = true, "allinone-shutdown"));

        Path home = Path.of(env("APP_HOME", "/app"));
        Path servicesRoot = home.resolve("services");
        Path configRoot = home.resolve("config");
        String profile = env("SPRING_PROFILES_ACTIVE", "prod");
        int maxRestarts = intEnv("SERVICE_MAX_RESTARTS", 5);
        int restartDelaySeconds = intEnv("SERVICE_RESTART_DELAY_SECONDS", 10);

        log("launcher", "单 JVM 启动器：服务数=" + SERVICES.size()
                + " profile=" + profile
                + " 堆上限=" + mb(Runtime.getRuntime().maxMemory()) + "M"
                + " 可用核=" + Runtime.getRuntime().availableProcessors()
                + " 服务目录=" + servicesRoot);

        List<Thread> threads = new ArrayList<>();
        for (ServiceSpec spec : SERVICES) {
            Path dir = servicesRoot.resolve(spec.name());
            if (!Files.isDirectory(dir.resolve("BOOT-INF"))) {
                throw new IllegalStateException("服务目录不完整（缺少 BOOT-INF）：" + dir);
            }
            List<String> serviceArgs = buildArgs(spec, configRoot, profile);
            log(spec.name(), "注册服务 mainClass=" + spec.mainClass()
                    + " port=" + spec.port() + " 参数=" + redact(serviceArgs));
            Thread thread = new Thread(
                    () -> supervise(spec, dir, serviceArgs, maxRestarts, restartDelaySeconds),
                    "svc-" + spec.name());
            thread.setDaemon(false);
            threads.add(thread);
            thread.start();
            if (spec.delaySeconds() > 0) {
                Thread.sleep(spec.delaySeconds() * 1000L);
            }
        }

        log("launcher", "全部服务已拉起，等待退出信号（SIGTERM 会触发各 Spring 上下文的关闭钩子）");
        for (Thread thread : threads) {
            thread.join();
        }
        log("launcher", "所有服务线程已结束，JVM 退出");
    }

    /** 构造单个服务的启动参数。 */
    private static List<String> buildArgs(ServiceSpec spec, Path configRoot, String profile) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("--spring.profiles.active=" + profile);
        // 控制台日志加服务名前缀：6 个服务共用一个 stdout，没有前缀排障时无法分辨来源
        args.add("--logging.pattern.console=%d{HH:mm:ss.SSS} %-5level [" + spec.name() + "] %logger{28} - %msg%n");
        Path config = configRoot.resolve(spec.name() + ".json");
        if (Files.isRegularFile(config)) {
            String json = expandPlaceholders(Files.readString(config, StandardCharsets.UTF_8));
            args.add("--spring.application.json=" + json);
        } else {
            log(spec.name(), "未找到 " + config + "，跳过 per-service 覆盖");
        }
        return args;
    }

    /** 守护一个服务：main 返回后交给端口看门狗；异常退出/端口掉线就重启。 */
    private static void supervise(ServiceSpec spec, Path dir, List<String> args,
                                 int maxRestarts, int restartDelaySeconds) {
        URLClassLoader current = null;
        for (int attempt = 1; !stopping; attempt++) {
            try {
                current = startOnce(spec, dir, args);
                watchPort(spec);
                if (stopping) {
                    log(spec.name(), "JVM 正在关闭，停止看门狗");
                    return;
                }
                log(spec.name(), "服务已停止（端口 " + spec.port() + " 不再监听）");
            } catch (Throwable t) {
                log(spec.name(), "启动/运行失败（第 " + attempt + " 次）：" + t);
                t.printStackTrace(System.out);
                closeQuietly(current);
                current = null;
            }
            if (stopping) {
                return;
            }
            if (attempt > maxRestarts) {
                log(spec.name(), "已连续失败 " + attempt + " 次，超过上限 " + maxRestarts
                        + "，退出 JVM 交给容器 restart 策略整体重启");
                System.out.flush();
                System.err.flush();
                Runtime.getRuntime().halt(1);
            }
            log(spec.name(), restartDelaySeconds + "s 后重启（第 " + (attempt + 1) + " 次尝试）");
            closeQuietly(current);
            current = null;
            if (!sleep(restartDelaySeconds * 1000L)) {
                return;
            }
        }
    }

    /**
     * 在独立类加载器里跑一次服务主类。
     *
     * <p>注意：web 服务的 main 会在上下文刷新后立刻返回，返回后**不能关闭类加载器**
     * （会让后续懒加载的类和资源抛 ClassNotFound/IOException），要等看门狗判定服务结束时才关。
     */
    private static URLClassLoader startOnce(ServiceSpec spec, Path dir, List<String> args) throws Exception {
        URLClassLoader loader = new URLClassLoader(classpath(dir), ClassLoader.getPlatformClassLoader());
        disableTomcatUrlHandlerFactory(spec, loader);

        Method main;
        try {
            main = Class.forName(spec.mainClass(), false, loader).getDeclaredMethod("main", String[].class);
            main.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            closeQuietly(loader);
            throw new IllegalStateException("无法加载主类 " + spec.mainClass() + "（检查 services/" + spec.name() + "）", e);
        }

        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        String previousName = current.getName();
        current.setContextClassLoader(loader);
        current.setName("svc-" + spec.name());
        try {
            main.invoke(null, (Object) args.toArray(new String[0]));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            closeQuietly(loader);
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } finally {
            current.setName(previousName);
            current.setContextClassLoader(previous);
        }
        return loader;
    }

    /**
     * 同一个 JVM 里多个 Tomcat 的已知冲突，必须先解除：
     * Tomcat 11 的 {@code StandardRoot.startInternal()} 会无条件调用
     * {@code TomcatURLStreamHandlerFactory.register()}，其构造函数直接调
     * {@code java.net.URL.setURLStreamHandlerFactory()}——而 JDK 只允许全局注册一次，
     * 于是第二个及之后的服务都会抛 {@code java.lang.Error: factory already defined}，
     * Tomcat 起不来（Tomcat 既不 try/catch 也不看 register() 的返回值）。
     *
     * <p>每个服务的类加载器里都有一份自己的 Tomcat 类，所以这里在每个类加载器里先调
     * {@code disable()}：静态 instance 已存在且 registered=false，随后的 register()
     * 直接返回 false，不再碰 JDK 全局工厂。代价是不注册 {@code war:} / {@code classpath:}
     * 协议处理器——本栈 6 个服务都是从展开目录启动的普通 jar 应用（不是 war），用不到。
     */
    private static void disableTomcatUrlHandlerFactory(ServiceSpec spec, URLClassLoader loader) {
        try {
            Class<?> factory = Class.forName(
                    "org.apache.catalina.webresources.TomcatURLStreamHandlerFactory", true, loader);
            factory.getMethod("disable").invoke(null);
            log(spec.name(), "已就地关闭 Tomcat 的 URLStreamHandlerFactory 全局注册（多应用同 JVM 必需）");
        } catch (ClassNotFoundException e) {
            // 没有内嵌 Tomcat 的服务（例如纯任务型应用）不需要处理
        } catch (ReflectiveOperationException e) {
            log(spec.name(), "警告：无法关闭 Tomcat URLStreamHandlerFactory： " + e);
        }
    }

    /**
     * 端口看门狗：先等端口监听（最多 {@value #PORT_UP_TIMEOUT_SECONDS} 秒），
     * 之后每 15 秒探一次；连续失败即判定服务已死，返回让调用方重启。
     */
    private static void watchPort(ServiceSpec spec) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PORT_UP_TIMEOUT_SECONDS);
        boolean up = false;
        while (!stopping && System.nanoTime() < deadline) {
            if (probe(spec.port())) {
                up = true;
                break;
            }
            if (!sleep(1000L)) {
                return;
            }
        }
        if (!up) {
            throw new IllegalStateException("端口 " + spec.port() + " 在 " + PORT_UP_TIMEOUT_SECONDS + " 秒内没有开始监听");
        }
        log(spec.name(), "端口 " + spec.port() + " 已监听，进入存活探测");

        int failures = 0;
        while (!stopping) {
            if (!sleep(PORT_PROBE_INTERVAL_SECONDS * 1000L)) {
                return;
            }
            if (probe(spec.port())) {
                failures = 0;
                continue;
            }
            failures++;
            log(spec.name(), "端口 " + spec.port() + " 探测失败（" + failures + "/"
                    + PORT_PROBE_FAILURES_BEFORE_RESTART + "）");
            if (failures >= PORT_PROBE_FAILURES_BEFORE_RESTART) {
                throw new IllegalStateException("端口 " + spec.port() + " 连续 "
                        + failures + " 次探测失败，判定服务已死");
            }
        }
    }

    private static boolean probe(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 组装 exploded Spring Boot jar 的类路径：BOOT-INF/classes + BOOT-INF/lib/*.jar。 */
    private static URL[] classpath(Path dir) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(dir.resolve("BOOT-INF/classes").toUri().toURL());
        Path lib = dir.resolve("BOOT-INF/lib");
        try (Stream<Path> files = Files.list(lib)) {
            files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            urls.add(path.toUri().toURL());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return urls.toArray(new URL[0]);
    }

    /** 把 config/*.json 里的 ${ENV} 换成环境变量值。 */
    private static String expandPlaceholders(String template) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = System.getenv(name);
            if (value == null) {
                log("launcher", "警告：环境变量 " + name + " 未设置，按空字符串代入");
                value = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String redact(List<String> args) {
        List<String> masked = new ArrayList<>(args.size());
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq > 0 && SECRET_KEY.matcher(arg.substring(0, eq)).find()) {
                masked.add(arg.substring(0, eq) + "=***");
            } else {
                masked.add(arg);
            }
        }
        return String.join(" ", masked);
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已经结束的服务
        }
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static void log(String who, String message) {
        System.out.println(LocalTime.now().format(TS) + "  INFO [" + who + "] AllInOneLauncher - " + message);
        System.out.flush();
    }

    private AllInOneLauncher() {
    }
}
