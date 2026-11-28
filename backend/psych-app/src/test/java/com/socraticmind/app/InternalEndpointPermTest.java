package com.socraticmind.app;

import com.socraticmind.app.security.RequirePerm;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台的写接口必须写明"归谁"。这条测试是给未来的自己看的：
 *
 * 权限散着写的时候，漏标一个接口不会有任何报错——上线后谁都能调，直到有人问
 * "他怎么也能改这个"。前半年的两次发现都是这么来的（会话原文、运行参数）。
 *
 * 判据：/api/internal 下的控制器，凡是非 GET 的映射，方法或类上必须有 @RequirePerm。
 * 需要按请求内容判断的（如 config/rollback 按 kind 分），列进 DYNAMIC 白名单并写明原因。
 */
class InternalEndpointPermTest {

    /** 按 kind 现场判断的接口：保存那侧有注解，回滚这侧在方法体里查。 */
    private static final Set<String> DYNAMIC = Set.of("rollback");

    @Test
    void 后台写接口都要标注职责() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Class<?> type : controllers()) {
            boolean classAnnotated = type.isAnnotationPresent(RequirePerm.class);
            for (Method m : type.getDeclaredMethods()) {
                if (!isWrite(m)) continue;
                if (classAnnotated || m.isAnnotationPresent(RequirePerm.class)) continue;
                if (DYNAMIC.contains(m.getName())) continue;
                missing.add(type.getSimpleName() + "#" + m.getName());
            }
        }
        assertTrue(missing.isEmpty(),
                "以下后台写接口没有标注 @RequirePerm，任何非只读身份都能调：" + missing);
    }

    private static boolean isWrite(Method m) {
        return m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class)
                || m.isAnnotationPresent(PatchMapping.class);
    }

    /** 扫源码目录而不是反射包扫描：这里只要"哪些类挂了 /api/internal"，不需要启动容器。 */
    private static List<Class<?>> controllers() throws IOException, ClassNotFoundException {
        Path dir = Path.of("src/main/java/com/socraticmind/app/web");
        List<Class<?>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith("Controller.java")) continue;
                String cls = "com.socraticmind.app.web." + name.replace(".java", "");
                Class<?> type = Class.forName(cls);
                if (!type.isAnnotationPresent(RestController.class)) continue;
                RequestMapping rm = type.getAnnotation(RequestMapping.class);
                boolean internal = rm != null && rm.value().length > 0 && rm.value()[0].startsWith("/api/internal");
                if (internal) out.add(type);
            }
        }
        return out;
    }
}
