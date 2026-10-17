package moon.allinone;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 镜像构建期把 Spring Boot fat jar 解包成「classes + lib/*.jar」目录树。
 *
 * <p>为什么不用 unzip/jar：基座镜像（eclipse-temurin:*）里既没有 {@code unzip} 也没有 {@code jar}，
 * 而 JDK 自带的 {@code java.util.zip} 就能干这件事——这样构建不需要任何额外工具或额外基础镜像，
 * 也让最终镜像的目录结构与「脚本在 Windows 侧解包」完全一致，可复现。
 *
 * <p>用法：{@code java -cp all-in-one-launcher.jar moon.allinone.Unzip <jar目录> <输出目录>}
 * 每个 jar 按文件名（去掉 .jar）解到 {@code <输出目录>/<名字>/}。
 */
public final class Unzip {

    private static final int BUFFER = 1 << 16;

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法: Unzip <jar目录> <输出目录>");
        }
        Path sourceDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);

        try (Stream<Path> jars = Files.list(sourceDir)) {
            for (Path jar : jars.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                String name = jar.getFileName().toString().replaceFirst("\\.jar$", "");
                Path target = outDir.resolve(name);
                Files.createDirectories(target);
                long files = extract(jar, target);
                System.out.println("[unpack] " + jar.getFileName() + " -> " + target + " (" + files + " 个文件)");
            }
        }
        System.out.println("[unpack] 完成，输出目录: " + outDir.toAbsolutePath());
    }

    private static long extract(Path jar, Path target) throws IOException {
        long count = 0;
        byte[] buffer = new byte[BUFFER];
        try (InputStream raw = Files.newInputStream(jar);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/")) {
                    Files.createDirectories(target.resolve(name));
                    continue;
                }
                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("非法 entry（zip-slip）: " + name);
                }
                Files.createDirectories(out.getParent());
                // 同名 entry 直接覆盖：Spring Boot jar 里偶发重复 entry 时不能让构建失败
                Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return count;
    }

    private Unzip() {
    }
}
