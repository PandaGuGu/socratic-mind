package com.socraticmind.app.seed;

import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.service.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

/**
 * 启动时灌入示例语料（仅当知识库为空）。
 * 语料明确标注为"开发用示例，非权威指南"，避免被误当作可引用的临床依据。
 */
@Configuration
@ConditionalOnProperty(name = "psych.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedKnowledgeLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedKnowledgeLoader.class);

    private static final Map<String, String> TITLES = Map.of(
            "cbt-model.md", "示例资料：认知行为疗法的认知模型",
            "anxiety.md", "示例资料：焦虑的常见表现与维持因素",
            "scope.md", "示例资料：本系统的能力边界"
    );

    @Bean
    ApplicationRunner seedRunner(KnowledgeStore store, IngestionService ingestion) {
        return args -> {
            if (store.countChunks() > 0) {
                log.info("知识库非空（{} 块），跳过示例语料灌入", store.countChunks());
                return;
            }
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:seed/*.md");
            Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));

            for (Resource resource : resources) {
                String fileName = String.valueOf(resource.getFilename());
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String title = TITLES.getOrDefault(fileName, fileName);
                IngestCommand cmd = new IngestCommand("seed", title,
                        "内部示例语料（非权威指南，仅用于开发验证）", null, "zh", true, content);
                var result = ingestion.ingest(cmd);
                log.info("示例语料入库 {} → {} 子块 / {} 证据", title, result.childChunks(), result.evidenceCount());
            }
        };
    }
}
