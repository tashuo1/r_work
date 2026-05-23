package com.ontology.lite.service;

import com.ontology.lite.model.PlatformModels.PublishRequest;
import com.ontology.lite.model.PlatformModels.QueryRequest;
import com.ontology.lite.model.PlatformModels.ScanRequest;
import com.ontology.lite.model.PlatformModels.ScheduleRequest;
import com.ontology.lite.model.PlatformModels.ComputeResourceRequest;
import com.ontology.lite.model.PlatformModels.DataSourceRequest;
import com.ontology.lite.model.PlatformModels.DataStandardSkillRequest;
import com.ontology.lite.model.PlatformModels.DataStandardSkillResult;
import com.ontology.lite.model.PlatformModels.DataStandardGenerateRequest;
import com.ontology.lite.model.PlatformModels.ModelVersion;
import com.ontology.lite.model.PlatformModels.ReviewDecisionRequest;
import com.ontology.lite.model.PlatformModels.TermRootRequest;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyPlatformServiceTest {
    @Test
    void rejectsNonSelectSql() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        assertThatThrownBy(() -> service.submitQuery(new QueryRequest("删除数据", "s1", "r1", "DELETE FROM ontology_instance.employee")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("仅允许 SELECT");
    }

    @Test
    void validatesTermRootCode() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        assertThatThrownBy(() -> service.saveTermRoot(new TermRootRequest("HeTong_01", "合同", "投资域", "错误编码")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("词根编码");
    }

    @Test
    void disabledDatasourceCannotScan() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        service.toggleDataSource(1, false);

        assertThatThrownBy(() -> service.scanMetadata(new ScanRequest(1L, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("数据源已停用");
    }

    @Test
    void publishingRequiresConfirmation() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        long versionId = service.generateCandidates(new ScanRequest(1L, null)).versionId();

        assertThatThrownBy(() -> service.publishVersion(versionId, new PublishRequest(false, "tester", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("二次确认");
    }

    @Test
    void generatingCandidateArchivesPreviousDraftsAndKeepsOnlyLatestDraft() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        long firstDraft = service.generateCandidates(new ScanRequest(1L, null)).versionId();
        long secondDraft = service.generateCandidates(new ScanRequest(1L, null)).versionId();

        assertThat(service.listVersions().stream().filter(version -> "DRAFT".equals(version.status()))).hasSize(1);
        assertThat(service.listVersions().stream().filter(version -> version.id() == secondDraft).findFirst())
            .hasValueSatisfying(version -> assertThat(version.status()).isEqualTo("DRAFT"));
        assertThat(service.listVersions().stream().filter(version -> version.id() == firstDraft).findFirst())
            .hasValueSatisfying(version -> assertThat(version.status()).isEqualTo("INACTIVE"));
    }

    @Test
    void rollbackIgnoresArchivedDraftsThatWereNeverActive() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        long archivedDraft = service.generateCandidates(new ScanRequest(1L, null)).versionId();
        long latestDraft = service.generateCandidates(new ScanRequest(1L, null)).versionId();
        service.publishVersion(latestDraft, new PublishRequest(true, "tester", "发布最新候选"));
        var rolledBack = service.rollbackLatest(1);

        assertThat(rolledBack.id()).isNotEqualTo(archivedDraft);
        assertThat(service.activeVersion().activatedAt()).isNotNull();
    }

    @Test
    void startupArchivesSupersededDraftsFromPersistedState() {
        var mapper = new com.ontology.lite.mapper.InMemoryOntologyDataMapper() {
            @Override
            public boolean initialized() {
                return true;
            }
        };
        mapper.versions().put(1L, new ModelVersion(1L, "V1", "ACTIVE", true, 1, 1, 0, 1.0, Instant.now(), Instant.now()));
        mapper.versions().put(2L, new ModelVersion(2L, "V2", "DRAFT", false, 1, 1, 0, 0.78, Instant.now(), null));
        mapper.versions().put(3L, new ModelVersion(3L, "V3", "DRAFT", false, 1, 1, 0, 0.78, Instant.now(), null));

        OntologyPlatformService service = new OntologyPlatformService(mapper);

        assertThat(service.listVersions().stream().filter(version -> "DRAFT".equals(version.status()))).hasSize(1);
        assertThat(service.listVersions().stream().filter(version -> version.id() == 3L).findFirst())
            .hasValueSatisfying(version -> assertThat(version.status()).isEqualTo("DRAFT"));
        assertThat(service.listVersions().stream().filter(version -> version.id() == 2L).findFirst())
            .hasValueSatisfying(version -> assertThat(version.status()).isEqualTo("INACTIVE"));
    }

    @Test
    void dashboardHasActiveVersionAndThreeSources() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        assertThat(service.dashboard().dataSourceCount()).isEqualTo(3);
        assertThat(service.dashboard().objectCount()).isGreaterThanOrEqualTo(4);
        assertThat(service.activeVersion().status()).isEqualTo("ACTIVE");
    }

    @Test
    void dashboardExposesRuntimeEnvironmentForTrustBanner() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var dashboard = service.dashboard();

        assertThat(dashboard.runtimeStoreType()).isEqualTo("H2");
        assertThat(dashboard.runtimeStoreSummary()).contains("本地内存状态");
        assertThat(dashboard.fallbackMode()).isTrue();
        assertThat(dashboard.environmentWarnings()).contains("当前为本地演示兜底状态，请勿误判为外部平台库数据");
        assertThat(dashboard.pendingActionCount()).isGreaterThan(0);
    }

    @Test
    void datasourceRequestBuildsJdbcUrlAndStoresDriverForRealConnectionTesting() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var source = service.updateDataSource(1, new DataSourceRequest(
            "DM_FINANCE",
            "农投财务测试库",
            "财务域",
            "DM",
            "127.0.0.1",
            5236,
            "FINANCE",
            "SYSDBA",
            "secret",
            4,
            null,
            null
        ));

        assertThat(source.jdbcUrl()).isEqualTo("jdbc:dm://127.0.0.1:5236/FINANCE");
        assertThat(source.driverClassName()).isEqualTo("dm.jdbc.driver.DmDriver");
        assertThat(source.passwordConfigured()).isTrue();
    }

    @Test
    void computeResourceCanBeCreatedInitializedActivatedAndProtectedFromDelete() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var resource = service.createComputeResource(new ComputeResourceRequest(
            "COMPUTE_MAIN",
            "主计算资源",
            "MYSQL",
            "127.0.0.1",
            3306,
            "root",
            "secret",
            "jdbc:h2:mem:compute-resource-test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "ontology_meta",
            "ontology_instance"
        ));
        var initialized = service.initializeComputeResource(resource.id());
        var activated = service.activateComputeResource(resource.id());

        assertThat(initialized.success()).isTrue();
        assertThat(initialized.createdTables()).isEqualTo(24);
        assertThat(activated.active()).isTrue();
        assertThat(activated.initialized()).isTrue();
        assertThat(service.activeComputeResource().id()).isEqualTo(resource.id());
        assertThat(service.dashboard().computeResourceStatus()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.deleteComputeResource(resource.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("当前生效计算资源");
    }

    @Test
    void computeResourceDefaultJdbcUrlTargetsDatabaseInstanceBeforeSchemaCreation() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var mysql = service.createComputeResource(new ComputeResourceRequest(
            "COMPUTE_URL_MYSQL",
            "MySQL计算资源",
            "MYSQL",
            "10.1.2.3",
            3306,
            "root",
            "secret",
            null,
            "ontology_meta",
            "ontology_instance"
        ));
        var dm = service.createComputeResource(new ComputeResourceRequest(
            "COMPUTE_URL_DM",
            "DM计算资源",
            "DM",
            "10.1.2.4",
            5236,
            "SYSDBA",
            "secret",
            null,
            "ontology_meta",
            "ontology_instance"
        ));

        assertThat(mysql.jdbcUrl()).isEqualTo("jdbc:mysql://10.1.2.3:3306/mysql?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=10000");
        assertThat(dm.jdbcUrl()).isEqualTo("jdbc:dm://10.1.2.4:5236");
    }

    @Test
    void datasourceDefaultMysqlJdbcUrlIncludesConnectionTimeouts() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var source = service.createDataSource(new DataSourceRequest(
            "MYSQL_FINANCE",
            "MySQL财务库",
            "财务域",
            "MYSQL",
            "10.1.2.5",
            3306,
            "finance",
            "readonly",
            "secret",
            4,
            null,
            null
        ));

        assertThat(source.jdbcUrl()).isEqualTo("jdbc:mysql://10.1.2.5:3306/finance?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=10000");
    }

    @Test
    void customMysqlDatasourceJdbcUrlIsNormalizedWithTimeouts() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var source = service.createDataSource(new DataSourceRequest(
            "MYSQL_CUSTOM",
            "MySQL自定义连接",
            "财务域",
            "MYSQL",
            "10.1.2.6",
            3306,
            "finance",
            "readonly",
            "secret",
            4,
            "jdbc:mysql://10.1.2.6:3306/finance?useSSL=false",
            null
        ));

        assertThat(source.jdbcUrl()).isEqualTo("jdbc:mysql://10.1.2.6:3306/finance?useSSL=false&connectTimeout=10000&socketTimeout=10000");
    }

    @Test
    void activeComputeResourceReceivesNormativeMetadataAndInstanceWrites() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:compute-normative-write;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        var resource = service.createComputeResource(new ComputeResourceRequest(
            "COMPUTE_WRITE",
            "规范表写入资源",
            "MYSQL",
            "127.0.0.1",
            3306,
            "sa",
            "compute-secret",
            jdbcUrl,
            "ontology_meta",
            "ontology_instance"
        ));
        service.initializeComputeResource(resource.id());
        service.activateComputeResource(resource.id());

        service.saveTermRoot(new TermRootRequest("ziyuan", "资源", "通用", "规范表写入验证"));
        service.triggerEtl("MANUAL");
        service.submitQuery(new QueryRequest("查询投资项目合同列表", "s1", "r-compute", null));

        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "compute-secret");
             var statement = connection.createStatement()) {
            assertThat(count(statement, "meta_om_term_root")).isGreaterThan(0);
            assertThat(count(statement, "meta_om_standard_field")).isGreaterThan(0);
            assertThat(count(statement, "meta_om_model_version")).isGreaterThan(0);
            assertThat(count(statement, "meta_om_etl_run_log")).isGreaterThan(0);
            assertThat(count(statement, "meta_om_query_history")).isGreaterThan(0);
            assertThat(count(statement, "meta_om_compute_resource")).isEqualTo(2);
            assertThat(count(statement, "meta_om_compute_resource_health")).isGreaterThan(0);
            try (var resultSet = statement.executeQuery("""
                SELECT COUNT(*)
                FROM meta_om_compute_resource
                WHERE compute_resource_id = %d
                  AND store_type IN ('META', 'INSTANCE')
                  AND database_name IN ('ontology_meta', 'ontology_instance')
                  AND username = 'sa'
                  AND password_value = 'compute-secret'
                """.formatted(resource.id()))) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
            assertThat(count(statement, "instance_om_object_instance")).isGreaterThan(0);
            assertThat(count(statement, "instance_om_relation_instance")).isGreaterThan(0);
            assertThat(count(statement, "instance_om_action_event")).isGreaterThan(0);
        }
    }

    @Test
    void dataStandardReviewDecisionsSyncToActiveComputeResourceTables() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:data-standard-compute-write;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        var resource = service.createComputeResource(new ComputeResourceRequest(
            "COMPUTE_STANDARD",
            "数据标准写入资源",
            "MYSQL",
            "127.0.0.1",
            3306,
            "sa",
            "compute-secret",
            jdbcUrl,
            "ontology_meta",
            "ontology_instance"
        ));
        service.initializeComputeResource(resource.id());
        service.activateComputeResource(resource.id());
        service.generateDataStandards(new DataStandardGenerateRequest(List.of(1L)));
        var draftRoot = service.listTermRoots().stream().filter(root -> "DRAFT".equals(root.status())).findFirst().orElseThrow();
        var draftField = service.listStandardFields().stream().filter(field -> "DRAFT".equals(field.status())).findFirst().orElseThrow();

        service.approveTermRoot(draftRoot.id(), new ReviewDecisionRequest("admin", "确认财务词根"));
        service.rejectStandardField(draftField.id(), new ReviewDecisionRequest("admin", "字段暂不采用"));

        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "compute-secret");
             var statement = connection.createStatement()) {
            try (var resultSet = statement.executeQuery("""
                SELECT COUNT(*)
                FROM meta_om_term_root
                WHERE root_code = '%s'
                  AND status = 'ACTIVE'
                  AND source_type = 'AI_SKILL'
                  AND review_note = '确认财务词根'
                """.formatted(draftRoot.code()))) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }
            try (var resultSet = statement.executeQuery("""
                SELECT COUNT(*)
                FROM meta_om_standard_field
                WHERE field_code = '%s'
                """.formatted(draftField.code()))) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(0);
            }
        }
    }

    private int count(java.sql.Statement statement, String tableName) throws Exception {
        try (var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    @Test
    void datasourceConnectionTestUsesRealJdbcAndReportsFailureForUnreachableDatabase() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        service.updateDataSource(1, new DataSourceRequest(
            "DM_FINANCE",
            "农投财务测试库",
            "财务域",
            "DM",
            "127.0.0.1",
            1,
            "FINANCE",
            "SYSDBA",
            "secret",
            4,
            null,
            null
        ));

        var result = service.testDataSource(1);

        assertThat(result.success()).isFalse();
        assertThat(result.readonly()).isFalse();
        assertThat(result.message()).contains("真实连接失败");
    }

    @Test
    void datasourceCanBeDeletedWithStoredPasswordAndAuditLog() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        service.updateDataSource(1, new DataSourceRequest(
            "DM_FINANCE",
            "农投财务测试库",
            "财务域",
            "DM",
            "127.0.0.1",
            5236,
            "FINANCE",
            "SYSDBA",
            "secret",
            4,
            null,
            null
        ));

        service.deleteDataSource(1);

        assertThat(service.listDataSources()).extracting("id").doesNotContain(1L);
        assertThat(service.changeLogs()).anySatisfy(log -> {
            assertThat(log.entityType()).isEqualTo("DATASOURCE");
            assertThat(log.action()).isEqualTo("DELETE");
            assertThat(log.summary()).contains("农投财务测试库");
        });
        assertThatThrownBy(() -> service.testDataSource(1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("数据源不存在");
    }

    @Test
    void acceptanceDoesNotMarkRealDatasourceChecksPassedBeforeSuccessfulJdbcTests() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var acceptance = service.acceptanceStatus();

        assertThat(acceptance.get("DS-01").toString()).doesNotStartWith("PASS");
        assertThat(acceptance.get("DS-02").toString()).doesNotStartWith("PASS");
        assertThat(acceptance.get("DS-01").toString()).contains("待");
    }

    @Test
    void acceptanceMarksApiGovernanceAsDeferredForPhaseOne() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var acceptance = service.acceptanceStatus();

        assertThat(acceptance.get("API-05").toString()).startsWith("DEFERRED");
        assertThat(acceptance.get("API-06").toString()).startsWith("DEFERRED");
    }

    @Test
    void querySafetyRejectsDirectSourceDatabase() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        assertThatThrownBy(() -> service.submitQuery(new QueryRequest("跨源", "s1", "r1", "SELECT * FROM dm_finance.voucher")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("禁止直接跨源库");
    }

    @Test
    void querySafetyRejectsStackedStatementsAndComments() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        assertThatThrownBy(() -> service.submitQuery(new QueryRequest("堆叠", "s1", "r1", "SELECT * FROM ontology_instance.employee; DROP TABLE om_object")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("禁止多语句");

        assertThatThrownBy(() -> service.submitQuery(new QueryRequest("注释", "s1", "r1", "SELECT * FROM ontology_instance.employee /* hidden */")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("禁止SQL注释");
    }

    @Test
    void querySafetyRejectsBlankSqlOverride() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        assertThatThrownBy(() -> service.submitQuery(new QueryRequest("空SQL", "s1", "r1", "   ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SQL不能为空");
    }

    @Test
    void rapidQuerySubmissionsHaveUniqueIds() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        Set<String> queryIds = new HashSet<>();

        for (int i = 0; i < 50; i++) {
            queryIds.add(service.submitQuery(new QueryRequest("查询投资项目合同列表", "s1", "r" + i, null)).queryId());
        }

        assertThat(queryIds).hasSize(50);
    }

    @Test
    void scheduleCanBeUpdatedAndDagIsVisible() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        service.updateT1Schedule(new ScheduleRequest("02:30", "ACTIVE"));

        assertThat(service.t1Schedule().runAt()).isEqualTo("02:30");
        assertThat(service.t1Schedule().dagNodes()).contains("对象抽取", "关系构建", "质量门禁", "快照发布");
    }

    @Test
    void queryResultCanBeChartedAndExported() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        var query = service.submitQuery(new QueryRequest("查询投资项目合同列表", "s1", "r1", null));

        assertThat(service.queryChart(query.queryId(), "bar").chartType()).isEqualTo("bar");
        assertThat(service.exportQueryCsv(query.queryId()).get("filename")).asString().endsWith(".csv");
    }

    @Test
    void openApiExposesPropertiesAndInstances() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        assertThat(service.openProperties()).isNotEmpty();
        assertThat(service.objectInstances("investment_project")).hasSize(2);
    }

    @Test
    void dictionaryExportEscapesCsvCells() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        service.saveTermRoot(new TermRootRequest("douhao", "逗号", "通用", "包含,逗号和\n换行"));

        String csv = service.exportDictionaryCsv().get("termRootsCsv").toString();

        assertThat(csv).contains("\"包含,逗号和\n换行\"");
    }

    @Test
    void aiDataStandardGenerationCreatesReviewableRootsAndFields() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());

        var result = service.generateDataStandards(new DataStandardGenerateRequest(List.of(1L, 2L)));

        assertThat(result.skillName()).isEqualTo("cizhu-shengcheng");
        assertThat(result.executionMode()).isEqualTo("LOCAL_SKILL_SERVICE");
        assertThat(result.skillPath()).contains("cizhu-shengcheng");
        assertThat(result.stage()).isEqualTo("WRITE_REVIEW_CANDIDATES");
        assertThat(result.warnings()).contains("真实 LLM 执行器未配置，当前使用本地 Skill 服务适配器");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.sourceCount()).isEqualTo(2);
        assertThat(result.createdRootCount()).isGreaterThan(0);
        assertThat(result.createdFieldCount()).isGreaterThan(0);
        assertThat(service.listTermRoots()).anySatisfy(root -> {
            assertThat(root.status()).isEqualTo("DRAFT");
            assertThat(root.sourceType()).isEqualTo("AI_SKILL");
            assertThat(root.confidenceLevel()).isNotBlank();
            assertThat(root.evidenceJson()).contains("sourceCode");
            assertThat(root.similarRoots()).isNotEmpty();
        });
        assertThat(service.listStandardFields()).anySatisfy(field -> {
            assertThat(field.status()).isEqualTo("DRAFT");
            assertThat(field.sourceType()).isEqualTo("AI_SKILL");
            assertThat(field.evidenceJson()).contains("sourceCode");
            assertThat(field.rootCodes()).isNotEmpty();
        });
    }

    @Test
    void repeatedAiDataStandardGenerationReportsSkippedExistingCandidates() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        service.generateDataStandards(new DataStandardGenerateRequest(List.of(1L)));

        var result = service.generateDataStandards(new DataStandardGenerateRequest(List.of(1L)));

        assertThat(result.createdRootCount()).isZero();
        assertThat(result.createdFieldCount()).isZero();
        assertThat(result.skippedRootCount() + result.skippedFieldCount()).isGreaterThan(0);
        assertThat(result.message()).contains("已有候选");
    }

    @Test
    void aiDataStandardGenerationUsesSkillExecutionClientBoundary() {
        RecordingSkillExecutionClient skillClient = new RecordingSkillExecutionClient();
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper(), skillClient);

        var result = service.generateDataStandards(new DataStandardGenerateRequest(List.of(1L)));

        assertThat(skillClient.requests).hasSize(1);
        assertThat(skillClient.requests.get(0).skillName()).isEqualTo("cizhu-shengcheng");
        assertThat(skillClient.requests.get(0).sources()).extracting("code").containsExactly("DM_FINANCE");
        assertThat(result.executionMode()).isEqualTo("TEST_SKILL_SERVICE");
        assertThat(result.createdRootCount()).isEqualTo(1);
        assertThat(result.createdFieldCount()).isEqualTo(1);
        assertThat(service.listTermRoots()).anySatisfy(root -> {
            assertThat(root.code()).isEqualTo("ceshi");
            assertThat(root.sourceType()).isEqualTo("AI_SKILL");
            assertThat(root.evidenceJson()).contains("skillExecutionId");
        });
    }

    @Test
    void reviewDecisionsApproveOrRejectDataStandardCandidates() {
        OntologyPlatformService service = new OntologyPlatformService(new com.ontology.lite.mapper.InMemoryOntologyDataMapper());
        service.generateDataStandards(new DataStandardGenerateRequest(List.of(1L)));
        var draftRoot = service.listTermRoots().stream().filter(root -> "DRAFT".equals(root.status())).findFirst().orElseThrow();
        var draftField = service.listStandardFields().stream().filter(field -> "DRAFT".equals(field.status())).findFirst().orElseThrow();

        var approvedRoot = service.approveTermRoot(draftRoot.id(), new ReviewDecisionRequest("admin", "确认进入标准词根"));
        var rejectedField = service.rejectStandardField(draftField.id(), new ReviewDecisionRequest("admin", "字段语义不清晰"));

        assertThat(approvedRoot.status()).isEqualTo("ACTIVE");
        assertThat(approvedRoot.reviewNote()).contains("确认进入标准词根");
        assertThat(rejectedField.status()).isEqualTo("REJECTED");
        assertThat(rejectedField.reviewNote()).contains("字段语义不清晰");
    }

    private static final class RecordingSkillExecutionClient implements SkillExecutionClient {
        private final List<DataStandardSkillRequest> requests = new ArrayList<>();

        @Override
        public DataStandardSkillResult generateDataStandards(DataStandardSkillRequest request) {
            requests.add(request);
            return new DataStandardSkillResult(
                "skill-test-1",
                request.skillName(),
                "TEST_SKILL_SERVICE",
                "/tmp/cizhu-shengcheng/SKILL.md",
                "CANDIDATE_GENERATED",
                7,
                List.of("测试 Skill 服务适配器"),
                List.of(new DataStandardSkillResult.RootCandidate(
                    "ceshi",
                    "测试",
                    "测试域",
                    "由测试 Skill 客户端生成的词根",
                    "DRAFT",
                    "AI_SKILL",
                    "CANDIDATE_HIGH",
                    "{\"skillExecutionId\":\"skill-test-1\"}",
                    "等待人工确认",
                    List.of()
                )),
                List.of(new DataStandardSkillResult.FieldCandidate(
                    "ceshi_bianma",
                    "测试编码",
                    "STRING",
                    "测试域",
                    "由测试 Skill 客户端生成的标准字段",
                    List.of("ceshi"),
                    "DRAFT",
                    "AI_SKILL",
                    "CANDIDATE_HIGH",
                    "{\"skillExecutionId\":\"skill-test-1\"}",
                    "等待人工确认"
                )),
                "测试 Skill 服务调用成功",
                Instant.now()
            );
        }
    }
}
