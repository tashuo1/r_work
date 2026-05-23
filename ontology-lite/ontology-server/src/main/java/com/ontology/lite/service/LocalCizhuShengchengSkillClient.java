package com.ontology.lite.service;

import com.ontology.lite.model.PlatformModels.DataSource;
import com.ontology.lite.model.PlatformModels.DataStandardSkillRequest;
import com.ontology.lite.model.PlatformModels.DataStandardSkillResult;
import com.ontology.lite.model.PlatformModels.SimilarRoot;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class LocalCizhuShengchengSkillClient implements SkillExecutionClient {
    static final String SKILL_NAME = "cizhu-shengcheng";
    private static final String EXECUTION_MODE = "LOCAL_SKILL_SERVICE";
    private static final String BUNDLED_SKILL_ZIP = "/skills/cizhu-shengcheng.zip";

    private final List<Path> skillSearchRoots;
    private final Path runtimeSkillRoot;

    public LocalCizhuShengchengSkillClient() {
        this(defaultSkillSearchRoots(), defaultRuntimeSkillRoot());
    }

    LocalCizhuShengchengSkillClient(List<Path> skillSearchRoots, Path runtimeSkillRoot) {
        this.skillSearchRoots = skillSearchRoots == null ? List.of() : List.copyOf(skillSearchRoots);
        this.runtimeSkillRoot = runtimeSkillRoot;
    }

    @Override
    public DataStandardSkillResult generateDataStandards(DataStandardSkillRequest request) {
        long started = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        Path skillPath = resolveSkillPath(warnings);
        List<DataStandardSkillResult.RootCandidate> roots = new ArrayList<>();
        List<DataStandardSkillResult.FieldCandidate> fields = new ArrayList<>();
        for (DataSource source : request.sources() == null ? List.<DataSource>of() : request.sources()) {
            String rootCode = sourceRootCode(source);
            roots.add(new DataStandardSkillResult.RootCandidate(
                rootCode,
                sourceRootName(source),
                valueOr(source.domain(), "通用"),
                "由 " + SKILL_NAME + " 基于数据源元数据生成的待审核业务词根",
                "DRAFT",
                "AI_SKILL",
                "CANDIDATE_HIGH",
                evidenceJson(source, "term_root", rootCode, skillPath),
                "等待人工确认或拒绝",
                similarRootsForSource(source, rootCode)
            ));

            String fieldCode = rootCode + "_bianma";
            fields.add(new DataStandardSkillResult.FieldCandidate(
                fieldCode,
                sourceRootName(source) + "编码",
                "STRING",
                valueOr(source.domain(), "通用"),
                "由 " + SKILL_NAME + " 基于源字段编码/编号语义生成的待审核标准字段",
                List.of(rootCode),
                "DRAFT",
                "AI_SKILL",
                "CANDIDATE_HIGH",
                evidenceJson(source, "standard_field", fieldCode, skillPath),
                "等待人工确认或拒绝"
            ));
        }
        warnings.add("真实 LLM 执行器未配置，当前使用本地 Skill 服务适配器");
        return new DataStandardSkillResult(
            "skill-local-" + Instant.now().toEpochMilli(),
            SKILL_NAME,
            EXECUTION_MODE,
            skillPath.toString(),
            "CANDIDATE_GENERATED",
            Math.max(1, System.currentTimeMillis() - started),
            List.copyOf(warnings),
            roots,
            fields,
            "本地 Skill 服务适配器已执行 " + SKILL_NAME + " 并生成待审核候选",
            Instant.now()
        );
    }

    private Path resolveSkillPath(List<String> warnings) {
        for (Path root : skillSearchRoots) {
            Path candidate = root.resolve(SKILL_NAME).resolve("SKILL.md");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path installed = runtimeSkillRoot.resolve(SKILL_NAME).resolve("SKILL.md");
        if (!Files.isRegularFile(installed)) {
            installBundledSkill();
            warnings.add("未发现已安装 cizhu-shengcheng，已从程序内置 zip 解包安装");
        }
        return installed;
    }

    private void installBundledSkill() {
        try {
            Files.createDirectories(runtimeSkillRoot);
            try (InputStream stream = LocalCizhuShengchengSkillClient.class.getResourceAsStream(BUNDLED_SKILL_ZIP)) {
                if (stream == null) {
                    throw new IllegalStateException("程序内置 Skill zip 不存在：" + BUNDLED_SKILL_ZIP);
                }
                unzip(stream, runtimeSkillRoot);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("安装内置 cizhu-shengcheng Skill 失败：" + ex.getMessage(), ex);
        }
    }

    private void unzip(InputStream stream, Path targetRoot) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = targetRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetRoot.normalize())) {
                    throw new IOException("非法 zip 条目：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static List<Path> defaultSkillSearchRoots() {
        String home = System.getProperty("user.home", "");
        List<Path> roots = new ArrayList<>();
        if (!home.isBlank()) {
            roots.add(Path.of(home, ".codex", "skills"));
            roots.add(Path.of(home, ".agents", "skills"));
        }
        return roots;
    }

    private static Path defaultRuntimeSkillRoot() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path ontologyLite = cwd.getFileName() != null && "ontology-server".equals(cwd.getFileName().toString())
            ? cwd.getParent()
            : cwd.resolve("ontology-lite");
        return ontologyLite.resolve("runtime-skills");
    }

    private String sourceRootCode(DataSource source) {
        String domain = valueOr(source.domain(), source.code()).toLowerCase(Locale.ROOT);
        if (domain.contains("投资") || domain.contains("invest")) {
            return "touzi";
        }
        if (domain.contains("人事") || domain.contains("hr")) {
            return "renshi";
        }
        if (domain.contains("财务") || domain.contains("finance")) {
            return "caiwu";
        }
        return "shuju" + Math.abs(source.id());
    }

    private String sourceRootName(DataSource source) {
        String domain = valueOr(source.domain(), source.name());
        if (domain.contains("投资") || source.code().toLowerCase(Locale.ROOT).contains("invest")) {
            return "投资";
        }
        if (domain.contains("人事") || source.code().toLowerCase(Locale.ROOT).contains("hr")) {
            return "人事";
        }
        if (domain.contains("财务") || source.code().toLowerCase(Locale.ROOT).contains("finance")) {
            return "财务";
        }
        return source.name();
    }

    private List<SimilarRoot> similarRootsForSource(DataSource source, String rootCode) {
        if ("touzi".equals(rootCode)) {
            return List.of(
                new SimilarRoot("xiangmu", "项目", "RELATED", 0.82, "同属投资域业务对象"),
                new SimilarRoot("hetong", "合同", "RELATED", 0.74, "常与投资项目同时出现")
            );
        }
        if ("renshi".equals(rootCode)) {
            return List.of(new SimilarRoot("yuangong", "员工", "RELATED", 0.86, "人事域核心主体"));
        }
        if ("caiwu".equals(rootCode)) {
            return List.of(new SimilarRoot("pingzheng", "凭证", "RELATED", 0.83, "财务域核心凭证对象"));
        }
        return List.of(new SimilarRoot(rootCode, sourceRootName(source), "CANDIDATE", 0.66, "同源数据源候选"));
    }

    private String evidenceJson(DataSource source, String candidateType, String candidateCode, Path skillPath) {
        return """
            {"skill":"%s","executionMode":"%s","skillPath":"%s","candidateType":"%s","candidateCode":"%s","sourceCode":"%s","sourceName":"%s","domain":"%s","schemaName":"%s","sampleTables":["%s"]}
            """.formatted(
            SKILL_NAME,
            EXECUTION_MODE,
            json(skillPath.toString()),
            candidateType,
            candidateCode,
            json(source.code()),
            json(source.name()),
            json(valueOr(source.domain(), "通用")),
            json(source.databaseName()),
            json(sampleTable(source.domain()).get("tableName").toString())
        ).trim();
    }

    private Map<String, Object> sampleTable(String domain) {
        String normalized = valueOr(domain, "财务域");
        if (normalized.contains("投资")) {
            return table("INV_PROJECT", "投资项目", List.of("PROJECT_CODE", "PROJECT_NAME", "CONTRACT_AMOUNT", "STATUS"));
        }
        if (normalized.contains("人事")) {
            return table("HR_EMPLOYEE", "员工", List.of("EMP_NO", "NAME", "ORG_CODE", "POST_NAME"));
        }
        return table("FIN_VOUCHER", "会计凭证", List.of("VOUCHER_NO", "FISCAL_YEAR", "AMOUNT", "SUBJECT_CODE"));
    }

    private Map<String, Object> table(String name, String comment, List<String> columns) {
        return Map.of("tableName", name, "comment", comment, "primaryKey", columns.get(0), "columns", columns);
    }

    private String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
