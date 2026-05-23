package com.ontology.lite.service;

import com.ontology.lite.model.PlatformModels.DataSource;
import com.ontology.lite.model.PlatformModels.DataStandardSkillRequest;
import com.ontology.lite.model.PlatformModels.StandardField;
import com.ontology.lite.model.PlatformModels.TermRoot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCizhuShengchengSkillClientTest {
    @TempDir
    Path tempDir;

    @Test
    void installsBundledSkillZipWhenSkillIsMissingFromSearchRoots() {
        Path missingSkillRoot = tempDir.resolve("missing-skills");
        Path runtimeSkillRoot = tempDir.resolve("runtime-skills");
        LocalCizhuShengchengSkillClient client = new LocalCizhuShengchengSkillClient(List.of(missingSkillRoot), runtimeSkillRoot);

        var result = client.generateDataStandards(new DataStandardSkillRequest(
            "cizhu-shengcheng",
            List.of(new DataSource(
                101L,
                "DM_TEST",
                "测试业务库",
                "测试域",
                "DM",
                "jdbc:dm://127.0.0.1:5236/TEST",
                "dm.jdbc.driver.DmDriver",
                "127.0.0.1",
                5236,
                "TEST",
                "readonly",
                false,
                "ACTIVE",
                true,
                4,
                "UNKNOWN",
                "待巡检",
                null
            )),
            List.of(),
            List.of()
        ));

        Path installedSkill = runtimeSkillRoot.resolve("cizhu-shengcheng").resolve("SKILL.md");
        assertThat(result.executionMode()).isEqualTo("LOCAL_SKILL_SERVICE");
        assertThat(result.skillPath()).isEqualTo(installedSkill.toString());
        assertThat(result.warnings()).contains("未发现已安装 cizhu-shengcheng，已从程序内置 zip 解包安装");
        assertThat(Files.exists(installedSkill)).isTrue();
        assertThat(result.rootCandidates()).isNotEmpty();
        assertThat(result.fieldCandidates()).isNotEmpty();
    }

    @Test
    void returnsCandidatesEvenWhenTheyAlreadyExistSoCallerCanReportSkippedCounts() {
        Path missingSkillRoot = tempDir.resolve("missing-skills");
        Path runtimeSkillRoot = tempDir.resolve("runtime-skills");
        LocalCizhuShengchengSkillClient client = new LocalCizhuShengchengSkillClient(List.of(missingSkillRoot), runtimeSkillRoot);

        var result = client.generateDataStandards(new DataStandardSkillRequest(
            "cizhu-shengcheng",
            List.of(new DataSource(
                101L,
                "DM_TEST",
                "测试业务库",
                "测试域",
                "DM",
                "jdbc:dm://127.0.0.1:5236/TEST",
                "dm.jdbc.driver.DmDriver",
                "127.0.0.1",
                5236,
                "TEST",
                "readonly",
                false,
                "ACTIVE",
                true,
                4,
                "UNKNOWN",
                "待巡检",
                null
            )),
            List.of(new TermRoot(1L, "shuju101", "测试业务库", "测试域", "", "DRAFT", Instant.now())),
            List.of(new StandardField(2L, "shuju101_bianma", "测试业务库编码", "STRING", "测试域", "", List.of("shuju101"), "DRAFT", Instant.now()))
        ));

        assertThat(result.rootCandidates()).extracting("code").contains("shuju101");
        assertThat(result.fieldCandidates()).extracting("code").contains("shuju101_bianma");
    }
}
