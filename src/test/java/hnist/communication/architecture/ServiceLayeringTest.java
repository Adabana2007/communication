package hnist.communication.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 约束 Service 层始终采用接口 + impl 实现类的结构。 */
class ServiceLayeringTest {

    private static final Pattern SERVICE_ANNOTATION = Pattern.compile("(?m)^\\s*@Service\\b");
    private static final Pattern PACKAGE_NAME = Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);");
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
            "\\bpublic\\s+(?:final\\s+)?class\\s+(\\w+)(?:<[^>]*>)?[^{]*\\bimplements\\s+[^{]+\\{",
            Pattern.DOTALL);

    @Test
    void everyServiceImplementationIsAnInterfaceImplementationInImplPackage() throws Exception {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();
        try (var sources = Files.walk(sourceRoot)) {
            List<Path> serviceSources = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(this::isService)
                    .toList();
            assertFalse(serviceSources.isEmpty(), "未找到任何 @Service 类");
            for (Path source : serviceSources) {
                validate(sourceRoot, source, violations);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Service 分层不符合规范:\n" + String.join("\n", violations));
    }

    @Test
    void serviceRootOnlyContainsInterfaces() throws Exception {
        Path serviceRoot = Path.of("src", "main", "java", "hnist", "communication", "service");
        List<String> violations = new ArrayList<>();
        try (var sources = Files.list(serviceRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (!Files.readString(source).contains("public interface")) {
                    violations.add(serviceRoot.relativize(source).toString().replace('\\', '/') + " 不是 Service 接口");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "service 根包只能存放接口:\n" + String.join("\n", violations));
    }

    private boolean isService(Path path) {
        try {
            return SERVICE_ANNOTATION.matcher(Files.readString(path)).find();
        } catch (Exception e) {
            throw new IllegalStateException("读取 Service 源码失败: " + path, e);
        }
    }

    private void validate(Path sourceRoot, Path source, List<String> violations) throws Exception {
        String content = Files.readString(source);
        String relativePath = sourceRoot.relativize(source).toString().replace('\\', '/');
        Matcher packageMatcher = PACKAGE_NAME.matcher(content);
        Matcher classMatcher = CLASS_DECLARATION.matcher(content);
        if (!packageMatcher.find()) {
            violations.add(relativePath + " 缺少 package 声明");
            return;
        }
        String packageName = packageMatcher.group(1);
        if (!"impl".equals(source.getParent().getFileName().toString()) || !packageName.endsWith(".impl")) {
            violations.add(relativePath + " 的 Service 实现必须位于 impl 包");
        }
        if (!classMatcher.find()) {
            violations.add(relativePath + " 未实现任何 Service 接口");
            return;
        }
        if (!classMatcher.group(1).endsWith("Impl")) {
            violations.add(relativePath + " 的 Service 实现类必须以 Impl 结尾");
        }
    }
}
