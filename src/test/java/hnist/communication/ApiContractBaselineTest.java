package hnist.communication;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 固化对外 HTTP 路由与 JSON 属性名，防止规范整改改变现有接口。 */
class ApiContractBaselineTest {

    private static final Path BASELINE = Path.of("src/test/resources/api-contract-baseline.txt");
    private static final Path ACTUAL = Path.of("target/api-contract-actual.txt");

    @Test
    void publicApiShapeRemainsUnchanged() throws Exception {
        List<String> actual = inspectApi();
        Files.createDirectories(ACTUAL.getParent());
        Files.write(ACTUAL, actual, StandardCharsets.UTF_8);

        if (!Files.exists(BASELINE)) {
            throw new AssertionError("接口基线不存在，请将 " + ACTUAL.toAbsolutePath() + " 复制到 " + BASELINE.toAbsolutePath());
        }

        List<String> expected = Files.readAllLines(BASELINE, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private List<String> inspectApi() throws Exception {
        List<String> result = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> controllers = scanner.findCandidateComponents("hnist.communication.controller");
        for (BeanDefinition definition : controllers) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            result.addAll(routesOf(controller));
            for (Class<?> nested : controller.getDeclaredClasses()) {
                if (nested.isRecord() || hasJacksonFields(nested)) {
                    result.add(jsonShapeOf(nested));
                }
            }
        }
        for (String packageName : List.of("dto", "vo", "entity")) {
            for (Class<?> type : classesIn("hnist.communication." + packageName)) {
                result.add(jsonShapeOf(type));
            }
        }
        // VideoFile 不映射数据库，但属于 /api/videos 的对外响应模型，必须继续纳入契约。
        result.add(jsonShapeOf(Class.forName("hnist.communication.model.VideoFile")));
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private List<String> routesOf(Class<?> controller) {
        String base = mappingPaths(controller.getAnnotation(RequestMapping.class));
        List<String> routes = new ArrayList<>();
        Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getAnnotation(GetMapping.class) != null
                        || method.getAnnotation(PostMapping.class) != null
                        || method.getAnnotation(PutMapping.class) != null
                        || method.getAnnotation(DeleteMapping.class) != null
                        || method.getAnnotation(PatchMapping.class) != null
                        || method.getAnnotation(RequestMapping.class) != null)
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> routes.addAll(routeOf(controller, base, method)));
        return routes;
    }

    private List<String> routeOf(Class<?> controller, String base, Method method) {
        MappingDescriptor descriptor = descriptorOf(method);
        List<String> result = new ArrayList<>();
        for (String mappingPath : descriptor.paths()) {
            String path = joinPath(base, mappingPath);
            result.add("ROUTE " + descriptor.verbs() + " " + path + " " + controller.getSimpleName()
                    + "#" + method.getName() + " -> " + method.getGenericReturnType().getTypeName());
        }
        return result;
    }

    private MappingDescriptor descriptorOf(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) {
            return new MappingDescriptor("GET", pathsOf(get.value(), get.path()));
        }
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) {
            return new MappingDescriptor("POST", pathsOf(post.value(), post.path()));
        }
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) {
            return new MappingDescriptor("PUT", pathsOf(put.value(), put.path()));
        }
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            return new MappingDescriptor("DELETE", pathsOf(delete.value(), delete.path()));
        }
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) {
            return new MappingDescriptor("PATCH", pathsOf(patch.value(), patch.path()));
        }
        RequestMapping request = method.getAnnotation(RequestMapping.class);
        String verbs = request.method().length == 0 ? "ANY"
                : Arrays.stream(request.method()).map(RequestMethod::name).sorted().reduce((a, b) -> a + "," + b).orElse("ANY");
        return new MappingDescriptor(verbs, pathsOf(request.value(), request.path()));
    }

    private String mappingPaths(RequestMapping mapping) {
        if (mapping == null) {
            return "";
        }
        List<String> paths = pathsOf(mapping.value(), mapping.path());
        return paths.getFirst();
    }

    private List<String> pathsOf(String[] values, String[] paths) {
        String[] source = values.length > 0 ? values : paths;
        if (source.length == 0) {
            return List.of("");
        }
        return Arrays.asList(source);
    }

    private String joinPath(String base, String path) {
        String normalizedBase = normalizePath(base);
        String normalizedPath = normalizePath(path);
        if (normalizedBase.isEmpty()) {
            return normalizedPath.isEmpty() ? "/" : normalizedPath;
        }
        if (normalizedPath.isEmpty()) {
            return normalizedBase;
        }
        return normalizedBase + normalizedPath;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String jsonShapeOf(Class<?> type) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        TreeSet<String> properties = new TreeSet<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                properties.add(component.getName());
            }
        } else {
            for (BeanPropertyDefinition property : mapper.getSerializationConfig().introspect(javaType).findProperties()) {
                properties.add(property.getName());
            }
        }
        return "JSON " + type.getName() + " -> " + String.join(",", properties);
    }

    private boolean hasJacksonFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).anyMatch(field -> !field.isSynthetic());
    }

    private List<Class<?>> classesIn(String packageName) throws IOException, ClassNotFoundException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:" + packageName.replace('.', '/') + "/*.class");
        List<Class<?>> classes = new ArrayList<>();
        for (Resource resource : resources) {
            String url = resource.getURL().toString();
            int marker = url.indexOf("!/");
            if (marker >= 0) {
                url = url.substring(marker + 2);
            }
            int classesMarker = url.indexOf("hnist/communication/");
            if (classesMarker < 0) {
                continue;
            }
            String className = url.substring(classesMarker, url.length() - ".class".length()).replace('/', '.');
            if (!className.contains("$")) {
                classes.add(Class.forName(className));
            }
        }
        return classes;
    }

    private record MappingDescriptor(String verbs, List<String> paths) {
    }
}
