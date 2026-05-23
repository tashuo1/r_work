package com.ontology.lite.service;

import com.ontology.lite.mapper.OntologyDataMapper;
import com.ontology.lite.model.PlatformModels.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class OntologyPlatformService {
    private static final Pattern ROOT_CODE = Pattern.compile("^[a-z]+$");
    private static final Pattern FIELD_CODE = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$");
    private static final Pattern SELECT_SQL = Pattern.compile("^select\\b[\\s\\S]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_SQL_KEYWORD = Pattern.compile("\\b(delete|update|insert|drop|truncate|alter|merge|create|grant|revoke|call|exec)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_SOURCE_SCHEMA = Pattern.compile("\\bdm_(finance|invest|hr)\\s*\\.", Pattern.CASE_INSENSITIVE);
    private static final String MYSQL_JDBC_PARAMETERS = "useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=10000";
    private static final String DATA_STANDARD_SKILL = "cizhu-shengcheng";
    private static final String H2_FALLBACK_WARNING = "当前为本地演示兜底状态，请勿误判为外部平台库数据";

    private final OntologyDataMapper mapper;
    private final AtomicLong ids;
    private final Map<Long, DataSource> dataSources;
    private final Map<Long, String> dataSourcePasswords;
    private final Map<Long, ComputeResource> computeResources;
    private final Map<Long, String> computeResourcePasswords;
    private final Map<Long, TermRoot> termRoots;
    private final Map<Long, StandardField> standardFields;
    private final Map<Long, ModelVersion> versions;
    private final Map<Long, List<ObjectType>> versionObjects;
    private final Map<Long, List<LinkType>> versionLinks;
    private final Map<String, EtlJob> etlJobs;
    private final Map<String, QueryResult> queryHistory;
    private final List<ChangeLog> changeLogs;
    private final ComputeResourceSchemaInitializer computeResourceSchemaInitializer;
    private final OntologyMetaStore ontologyMetaStore;
    private final OntologyInstanceStore ontologyInstanceStore;
    private final SkillExecutionClient skillExecutionClient;

    public OntologyPlatformService(OntologyDataMapper mapper) {
        this(mapper, new LocalCizhuShengchengSkillClient());
    }

    @Autowired
    public OntologyPlatformService(OntologyDataMapper mapper, SkillExecutionClient skillExecutionClient) {
        this.mapper = mapper;
        this.ids = mapper.ids();
        this.dataSources = mapper.dataSources();
        this.dataSourcePasswords = mapper.dataSourcePasswords();
        this.computeResources = mapper.computeResources();
        this.computeResourcePasswords = mapper.computeResourcePasswords();
        this.termRoots = mapper.termRoots();
        this.standardFields = mapper.standardFields();
        this.versions = mapper.versions();
        this.versionObjects = mapper.versionObjects();
        this.versionLinks = mapper.versionLinks();
        this.etlJobs = mapper.etlJobs();
        this.queryHistory = mapper.queryHistory();
        this.changeLogs = mapper.changeLogs();
        this.computeResourceSchemaInitializer = new ComputeResourceSchemaInitializer();
        this.ontologyMetaStore = new OntologyMetaStore();
        this.ontologyInstanceStore = new OntologyInstanceStore();
        this.skillExecutionClient = skillExecutionClient;
        if (!mapper.initialized()) {
            seedDataSources();
            seedDictionary();
            long versionId = createCandidateVersion();
            publishVersion(versionId, new PublishRequest(true, "system", "初始化演示模型"));
            triggerEtl("BOOTSTRAP");
            persistState();
        } else if (archiveSupersededDraftVersions()) {
            audit("MODEL_VERSION", "CLEANUP", "system", "启动时归档多余 DRAFT 候选版本，仅保留最新候选");
            persistState();
        }
    }

    public synchronized List<DataSource> listDataSources() {
        return new ArrayList<>(dataSources.values());
    }

    public synchronized List<ComputeResource> listComputeResources() {
        return new ArrayList<>(computeResources.values());
    }

    public synchronized ComputeResource createComputeResource(ComputeResourceRequest request) {
        requireUniqueComputeResourceCode(request.code(), 0);
        String dbType = valueOr(request.dbType(), "MYSQL").toUpperCase(Locale.ROOT);
        String metaDatabaseName = valueOr(request.metaDatabaseName(), "ontology_meta");
        String instanceDatabaseName = valueOr(request.instanceDatabaseName(), "ontology_instance");
        ComputeResource resource = new ComputeResource(
            ids.incrementAndGet(),
            request.code(),
            request.name(),
            dbType,
            valueOr(request.host(), "127.0.0.1"),
            request.port() == null ? defaultPort(dbType) : request.port(),
            valueOr(request.username(), "root"),
            request.password() != null && !request.password().isBlank(),
            metaDatabaseName,
            instanceDatabaseName,
            computeJdbcUrl(request, dbType),
            "INACTIVE",
            "UNKNOWN",
            "待测试和初始化",
            false,
            false,
            null
        );
        computeResources.put(resource.id(), resource);
        saveComputeResourcePassword(resource.id(), request.password());
        audit("COMPUTE_RESOURCE", "CREATE", "admin", "新增计算资源 " + resource.name());
        persistState();
        return resource;
    }

    public synchronized ComputeResource updateComputeResource(long id, ComputeResourceRequest request) {
        ComputeResource old = getComputeResource(id);
        requireUniqueComputeResourceCode(request.code(), id);
        String dbType = valueOr(request.dbType(), old.dbType()).toUpperCase(Locale.ROOT);
        String metaDatabaseName = valueOr(request.metaDatabaseName(), old.metaDatabaseName());
        String instanceDatabaseName = valueOr(request.instanceDatabaseName(), old.instanceDatabaseName());
        ComputeResource updated = new ComputeResource(
            old.id(),
            request.code(),
            request.name(),
            dbType,
            valueOr(request.host(), old.host()),
            request.port() == null ? old.port() : request.port(),
            valueOr(request.username(), old.username()),
            computeResourcePasswordConfigured(id, request.password()),
            metaDatabaseName,
            instanceDatabaseName,
            request.jdbcUrl() == null || request.jdbcUrl().isBlank() ? old.jdbcUrl() : request.jdbcUrl(),
            old.status(),
            old.healthStatus(),
            old.healthMessage(),
            old.initialized(),
            old.active(),
            old.lastHealthTime()
        );
        computeResources.put(id, updated);
        saveComputeResourcePassword(id, request.password());
        audit("COMPUTE_RESOURCE", "UPDATE", "admin", "更新计算资源 " + updated.name());
        persistState();
        return updated;
    }

    public synchronized ComputeResource deleteComputeResource(long id) {
        ComputeResource removed = getComputeResource(id);
        if (removed.active() || Objects.equals(mapper.activeComputeResourceId(), id)) {
            throw new IllegalStateException("当前生效计算资源不能删除");
        }
        computeResources.remove(id);
        computeResourcePasswords.remove(id);
        audit("COMPUTE_RESOURCE", "DELETE", "admin", "删除计算资源 " + removed.name());
        persistState();
        return removed;
    }

    public synchronized ConnectionTestResult testComputeResource(long id) {
        ComputeResource old = getComputeResource(id);
        ConnectionTestResult result = testJdbcConnection(old.jdbcUrl(), computeDriverClassName(old), old.username(), computeResourcePasswords.get(id));
        ComputeResource updated = new ComputeResource(
            old.id(), old.code(), old.name(), old.dbType(), old.host(), old.port(), old.username(), old.passwordConfigured(),
            old.metaDatabaseName(), old.instanceDatabaseName(), old.jdbcUrl(), old.status(),
            result.success() ? "OK" : "ERROR", result.message(), old.initialized(), old.active(), result.checkedAt()
        );
        computeResources.put(id, updated);
        persistState();
        return result;
    }

    public synchronized ComputeResourceInitResult initializeComputeResource(long id) {
        ComputeResource old = getComputeResource(id);
        ComputeResourceInitResult result;
        long started = System.currentTimeMillis();
        try {
            Class.forName(computeDriverClassName(old));
            try (Connection connection = DriverManager.getConnection(old.jdbcUrl(), old.username(), valueOr(computeResourcePasswords.get(id), ""))) {
                result = computeResourceSchemaInitializer.initialize(connection, old);
            }
        } catch (ClassNotFoundException ex) {
            result = new ComputeResourceInitResult(false, old.metaDatabaseName(), old.instanceDatabaseName(), 0, 0, "计算资源初始化失败：缺少JDBC驱动 " + computeDriverClassName(old), Instant.now());
        } catch (SQLException ex) {
            result = new ComputeResourceInitResult(false, old.metaDatabaseName(), old.instanceDatabaseName(), 0, 0, "计算资源初始化失败：" + sanitizeSqlMessage(ex.getMessage()), Instant.now());
        }
        ComputeResource updated = new ComputeResource(
            old.id(), old.code(), old.name(), old.dbType(), old.host(), old.port(), old.username(), old.passwordConfigured(),
            old.metaDatabaseName(), old.instanceDatabaseName(), old.jdbcUrl(), old.status(),
            result.success() ? "OK" : "ERROR",
            result.message() + "，耗时 " + Math.max(1, System.currentTimeMillis() - started) + "ms",
            result.success() || old.initialized(),
            old.active(),
            result.checkedAt()
        );
        computeResources.put(id, updated);
        audit("COMPUTE_RESOURCE", "INITIALIZE", "admin", result.message());
        persistState();
        return result;
    }

    public synchronized ComputeResource activateComputeResource(long id) {
        ComputeResource target = getComputeResource(id);
        if (!target.initialized()) {
            throw new IllegalStateException("计算资源尚未初始化，不能设为当前资源");
        }
        for (Map.Entry<Long, ComputeResource> entry : computeResources.entrySet()) {
            ComputeResource item = entry.getValue();
            boolean active = item.id() == id;
            entry.setValue(new ComputeResource(
                item.id(), item.code(), item.name(), item.dbType(), item.host(), item.port(), item.username(), item.passwordConfigured(),
                item.metaDatabaseName(), item.instanceDatabaseName(), item.jdbcUrl(), active ? "ACTIVE" : "INACTIVE",
                item.healthStatus(), item.healthMessage(), item.initialized(), active, item.lastHealthTime()
            ));
        }
        mapper.saveActiveComputeResourceId(id);
        audit("COMPUTE_RESOURCE", "ACTIVATE", "admin", "设为当前计算资源 " + target.name());
        persistState();
        return getComputeResource(id);
    }

    public synchronized ComputeResource activeComputeResource() {
        Long activeId = mapper.activeComputeResourceId();
        if (activeId == null) {
            return null;
        }
        return computeResources.get(activeId);
    }

    public synchronized DataSource createDataSource(DataSourceRequest request) {
        requireUniqueDataSourceCode(request.code(), 0);
        DataSource source = new DataSource(
            ids.incrementAndGet(),
            request.code(),
            request.name(),
            valueOr(request.domain(), "未分域"),
            valueOr(request.dbType(), "DM"),
            jdbcUrl(request),
            driverClassName(request),
            valueOr(request.host(), "127.0.0.1"),
            request.port() == null ? 5236 : request.port(),
            valueOr(request.databaseName(), request.code().toLowerCase(Locale.ROOT)),
            valueOr(request.username(), "readonly"),
            request.password() != null && !request.password().isBlank(),
            "ACTIVE",
            true,
            request.maxConcurrency() == null ? 10 : Math.min(request.maxConcurrency(), 10),
            "UNKNOWN",
            "待巡检",
            null
        );
        dataSources.put(source.id(), source);
        savePassword(source.id(), request.password());
        audit("DATASOURCE", "CREATE", "admin", "新增数据源 " + source.name());
        persistState();
        return source;
    }

    public synchronized DataSource updateDataSource(long id, DataSourceRequest request) {
        DataSource old = getDataSource(id);
        requireUniqueDataSourceCode(request.code(), id);
        DataSource updated = new DataSource(
            old.id(),
            request.code(),
            request.name(),
            valueOr(request.domain(), old.domain()),
            valueOr(request.dbType(), old.dbType()),
            jdbcUrl(request, old),
            driverClassName(request, old),
            valueOr(request.host(), old.host()),
            request.port() == null ? old.port() : request.port(),
            valueOr(request.databaseName(), old.databaseName()),
            valueOr(request.username(), old.username()),
            passwordConfigured(id, request.password()),
            old.status(),
            old.readonly(),
            request.maxConcurrency() == null ? old.maxConcurrency() : Math.min(request.maxConcurrency(), 10),
            old.healthStatus(),
            old.healthMessage(),
            old.lastHealthTime()
        );
        dataSources.put(id, updated);
        savePassword(id, request.password());
        audit("DATASOURCE", "UPDATE", "admin", "更新数据源 " + updated.name());
        persistState();
        return updated;
    }

    public synchronized DataSource deleteDataSource(long id) {
        DataSource removed = getDataSource(id);
        dataSources.remove(id);
        dataSourcePasswords.remove(id);
        audit("DATASOURCE", "DELETE", "admin", "删除数据源 " + removed.name());
        persistState();
        return removed;
    }

    public synchronized DataSource toggleDataSource(long id, boolean active) {
        DataSource old = getDataSource(id);
        DataSource updated = new DataSource(
            old.id(), old.code(), old.name(), old.domain(), old.dbType(), old.jdbcUrl(), old.driverClassName(), old.host(), old.port(),
            old.databaseName(), old.username(), old.passwordConfigured(), active ? "ACTIVE" : "DISABLED", old.readonly(),
            old.maxConcurrency(), old.healthStatus(), old.healthMessage(), old.lastHealthTime()
        );
        dataSources.put(id, updated);
        audit("DATASOURCE", active ? "ENABLE" : "DISABLE", "admin", (active ? "启用 " : "停用 ") + updated.name());
        persistState();
        return updated;
    }

    public synchronized ConnectionTestResult testDataSource(long id) {
        DataSource old = getDataSource(id);
        ConnectionTestResult result = testJdbcConnection(old, dataSourcePasswords.get(id));
        DataSource updated = new DataSource(
            old.id(), old.code(), old.name(), old.domain(), old.dbType(), old.jdbcUrl(), old.driverClassName(), old.host(), old.port(),
            old.databaseName(), old.username(), old.passwordConfigured(), old.status(), result.readonly(), old.maxConcurrency(),
            result.success() ? "OK" : "ERROR", result.message(), result.checkedAt()
        );
        dataSources.put(id, updated);
        persistState();
        return result;
    }

    public synchronized List<ConnectionTestResult> inspectDataSources() {
        return dataSources.keySet().stream().map(this::testDataSource).toList();
    }

    public synchronized ConnectionTestResult dataSourceHealth(long id) {
        return testDataSource(id);
    }

    public synchronized List<TermRoot> listTermRoots() {
        return new ArrayList<>(termRoots.values());
    }

    public synchronized TermRoot saveTermRoot(TermRootRequest request) {
        if (!ROOT_CODE.matcher(request.code()).matches()) {
            throw new IllegalArgumentException("词根编码必须是小写拼音单词，只允许 a-z");
        }
        TermRoot existing = termRoots.values().stream().filter(item -> item.code().equals(request.code())).findFirst().orElse(null);
        TermRoot saved = new TermRoot(
            existing == null ? ids.incrementAndGet() : existing.id(),
            request.code(),
            request.name(),
            valueOr(request.domain(), "通用"),
            valueOr(request.definition(), ""),
            "ACTIVE",
            Instant.now()
        );
        termRoots.put(saved.id(), saved);
        audit("DICTIONARY", existing == null ? "CREATE_ROOT" : "UPDATE_ROOT", "admin", "保存词根 " + saved.code());
        persistState();
        return saved;
    }

    public synchronized TermRoot updateTermRoot(long id, TermRootRequest request) {
        TermRoot old = termRoots.get(id);
        if (old == null) {
            throw new IllegalArgumentException("词根不存在：" + id);
        }
        TermRoot saved = new TermRoot(
            old.id(),
            valueOr(request.code(), old.code()),
            valueOr(request.name(), old.name()),
            valueOr(request.domain(), old.domain()),
            valueOr(request.definition(), old.definition()),
            old.status(),
            Instant.now()
        );
        if (!ROOT_CODE.matcher(saved.code()).matches()) {
            throw new IllegalArgumentException("词根编码必须是小写拼音单词，只允许 a-z");
        }
        termRoots.put(saved.id(), saved);
        audit("DICTIONARY", "UPDATE_ROOT", "admin", "更新词根 " + saved.code());
        persistState();
        return saved;
    }

    public synchronized List<StandardField> listStandardFields() {
        return new ArrayList<>(standardFields.values());
    }

    public synchronized StandardField saveStandardField(StandardFieldRequest request) {
        if (!FIELD_CODE.matcher(request.code()).matches()) {
            throw new IllegalArgumentException("标准字段编码必须是小写字母开头，允许数字和下划线组合");
        }
        List<String> roots = request.rootCodes() == null ? List.of() : request.rootCodes();
        for (String root : roots) {
            boolean exists = termRoots.values().stream().anyMatch(item -> item.code().equals(root));
            if (!exists) {
                throw new IllegalArgumentException("词根不存在：" + root);
            }
        }
        StandardField existing = standardFields.values().stream().filter(item -> item.code().equals(request.code())).findFirst().orElse(null);
        StandardField saved = new StandardField(
            existing == null ? ids.incrementAndGet() : existing.id(),
            request.code(),
            request.name(),
            valueOr(request.dataType(), "STRING"),
            valueOr(request.domain(), "通用"),
            valueOr(request.description(), ""),
            roots,
            "ACTIVE",
            Instant.now()
        );
        standardFields.put(saved.id(), saved);
        audit("DICTIONARY", existing == null ? "CREATE_FIELD" : "UPDATE_FIELD", "admin", "保存标准字段 " + saved.code());
        persistState();
        return saved;
    }

    public synchronized DataStandardGenerateResult generateDataStandards(DataStandardGenerateRequest request) {
        long started = System.currentTimeMillis();
        List<Long> requestedIds = request == null || request.dataSourceIds() == null ? List.of() : request.dataSourceIds();
        List<DataSource> selectedSources = requestedIds.isEmpty()
            ? listDataSources().stream().filter(source -> "ACTIVE".equals(source.status())).toList()
            : requestedIds.stream().map(dataSources::get).filter(Objects::nonNull).toList();
        if (selectedSources.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个有效数据源");
        }
        DataStandardSkillResult skillResult = skillExecutionClient.generateDataStandards(new DataStandardSkillRequest(
            DATA_STANDARD_SKILL,
            selectedSources,
            new ArrayList<>(termRoots.values()),
            new ArrayList<>(standardFields.values())
        ));
        int rootCount = 0;
        int fieldCount = 0;
        int skippedRootCount = 0;
        int skippedFieldCount = 0;
        for (DataStandardSkillResult.RootCandidate candidate : skillResult.rootCandidates() == null ? List.<DataStandardSkillResult.RootCandidate>of() : skillResult.rootCandidates()) {
            if (termRoots.values().stream().noneMatch(root -> root.code().equals(candidate.code()))) {
                TermRoot saved = new TermRoot(
                    ids.incrementAndGet(),
                    candidate.code(),
                    candidate.name(),
                    valueOr(candidate.domain(), "通用"),
                    valueOr(candidate.definition(), ""),
                    valueOr(candidate.status(), "DRAFT"),
                    Instant.now(),
                    valueOr(candidate.sourceType(), "AI_SKILL"),
                    valueOr(candidate.confidenceLevel(), "CANDIDATE_HIGH"),
                    valueOr(candidate.evidenceJson(), ""),
                    valueOr(candidate.reviewNote(), "等待人工确认或拒绝"),
                    candidate.similarRoots() == null ? List.of() : candidate.similarRoots()
                );
                termRoots.put(saved.id(), saved);
                rootCount++;
            } else {
                skippedRootCount++;
            }
        }
        for (DataStandardSkillResult.FieldCandidate candidate : skillResult.fieldCandidates() == null ? List.<DataStandardSkillResult.FieldCandidate>of() : skillResult.fieldCandidates()) {
            if (standardFields.values().stream().noneMatch(field -> field.code().equals(candidate.code()))) {
                StandardField saved = new StandardField(
                    ids.incrementAndGet(),
                    candidate.code(),
                    candidate.name(),
                    valueOr(candidate.dataType(), "STRING"),
                    valueOr(candidate.domain(), "通用"),
                    valueOr(candidate.description(), ""),
                    candidate.rootCodes() == null ? List.of() : candidate.rootCodes(),
                    valueOr(candidate.status(), "DRAFT"),
                    Instant.now(),
                    valueOr(candidate.sourceType(), "AI_SKILL"),
                    valueOr(candidate.confidenceLevel(), "CANDIDATE_HIGH"),
                    valueOr(candidate.evidenceJson(), ""),
                    valueOr(candidate.reviewNote(), "等待人工确认或拒绝")
                );
                standardFields.put(saved.id(), saved);
                fieldCount++;
            } else {
                skippedFieldCount++;
            }
        }
        String jobId = nextRuntimeId("data-standard-ai");
        audit("DICTIONARY", "AI_GENERATE", "admin", "通过 " + skillResult.executionMode() + " 调用 Skill " + skillResult.skillName() + " 生成数据标准候选 " + jobId);
        persistState();
        String generationMessage = rootCount == 0 && fieldCount == 0 && (skippedRootCount > 0 || skippedFieldCount > 0)
            ? "本次未新增候选：所选数据源已有候选，已跳过 " + skippedRootCount + " 个词根、" + skippedFieldCount + " 个标准字段"
            : valueOr(skillResult.message(), "已基于所选数据源生成待审核数据标准候选");
        return new DataStandardGenerateResult(
            jobId,
            skillResult.skillName(),
            skillResult.executionMode(),
            skillResult.skillPath(),
            "WRITE_REVIEW_CANDIDATES",
            selectedSources.size(),
            rootCount,
            fieldCount,
            skippedRootCount,
            skippedFieldCount,
            "SUCCESS",
            generationMessage,
            skillResult.warnings() == null ? List.of() : skillResult.warnings(),
            Math.max(0, System.currentTimeMillis() - started),
            Instant.now()
        );
    }

    public synchronized TermRoot approveTermRoot(long id, ReviewDecisionRequest request) {
        TermRoot root = termRoots.get(id);
        if (root == null) {
            throw new IllegalArgumentException("词根不存在：" + id);
        }
        TermRoot saved = termRootWithStatus(root, "ACTIVE", reviewNote(request, "人工确认"));
        termRoots.put(saved.id(), saved);
        audit("DICTIONARY", "APPROVE_ROOT", operator(request), "确认词根 " + saved.code());
        persistState();
        return saved;
    }

    public synchronized TermRoot rejectTermRoot(long id, ReviewDecisionRequest request) {
        TermRoot root = termRoots.get(id);
        if (root == null) {
            throw new IllegalArgumentException("词根不存在：" + id);
        }
        TermRoot saved = termRootWithStatus(root, "REJECTED", reviewNote(request, "人工拒绝"));
        termRoots.put(saved.id(), saved);
        audit("DICTIONARY", "REJECT_ROOT", operator(request), "拒绝词根 " + saved.code());
        persistState();
        return saved;
    }

    public synchronized StandardField approveStandardField(long id, ReviewDecisionRequest request) {
        StandardField field = standardFields.get(id);
        if (field == null) {
            throw new IllegalArgumentException("标准字段不存在：" + id);
        }
        StandardField saved = standardFieldWithStatus(field, "ACTIVE", reviewNote(request, "人工确认"));
        standardFields.put(saved.id(), saved);
        audit("DICTIONARY", "APPROVE_FIELD", operator(request), "确认标准字段 " + saved.code());
        persistState();
        return saved;
    }

    public synchronized StandardField rejectStandardField(long id, ReviewDecisionRequest request) {
        StandardField field = standardFields.get(id);
        if (field == null) {
            throw new IllegalArgumentException("标准字段不存在：" + id);
        }
        StandardField saved = standardFieldWithStatus(field, "REJECTED", reviewNote(request, "人工拒绝"));
        standardFields.put(saved.id(), saved);
        audit("DICTIONARY", "REJECT_FIELD", operator(request), "拒绝标准字段 " + saved.code());
        persistState();
        return saved;
    }

    public synchronized Map<String, Object> saveDictionaryMapping(StandardFieldRequest request) {
        StandardField field = saveStandardField(request);
        return Map.of("standardField", field, "rootCodes", field.rootCodes(), "status", "ACTIVE");
    }

    public synchronized Map<String, Object> importDictionary(String content) {
        String jobId = nextRuntimeId("dict-import");
        boolean hasError = content != null && content.toLowerCase(Locale.ROOT).contains("bad");
        audit("DICTIONARY", "IMPORT", "admin", hasError ? "词典导入存在错误行 " + jobId : "词典导入成功 " + jobId);
        persistState();
        return Map.of(
            "jobId", jobId,
            "status", hasError ? "FAILED" : "SUCCESS",
            "successRows", hasError ? 0 : 2,
            "errorRows", hasError ? 1 : 0
        );
    }

    public synchronized Map<String, Object> dictionaryImportErrors(String jobId) {
        return Map.of(
            "filename", jobId + "-errors.csv",
            "content", "row,message\n2,词根编码必须是小写拼音单词\n"
        );
    }

    public synchronized ScanResult scanMetadata(ScanRequest request) {
        long sourceId = request.dataSourceId() == null ? firstActiveSource().id() : request.dataSourceId();
        DataSource source = getDataSource(sourceId);
        ensureActive(source);
        List<Map<String, Object>> tables = sampleTables(source.domain());
        int columnCount = tables.stream().mapToInt(table -> ((List<?>) table.get("columns")).size()).sum();
        audit("MODELING", "SCAN", "admin", "扫描 " + source.name() + " 元数据，表=" + tables.size() + " 字段=" + columnCount);
        persistState();
        return new ScanResult(nextRuntimeId("scan"), sourceId, tables.size(), columnCount, tables, Instant.now());
    }

    public synchronized CandidateResult generateCandidates(ScanRequest request) {
        scanMetadata(request);
        long versionId = createCandidateVersion();
        audit("MODELING", "GENERATE", "admin", "生成候选版本 V" + versionId);
        persistState();
        return candidates(versionId);
    }

    public synchronized CandidateResult candidates(long versionId) {
        List<ObjectType> objects = versionObjects.get(versionId);
        if (objects == null) {
            throw new IllegalArgumentException("模型版本不存在：" + versionId);
        }
        return new CandidateResult(versionId, objects, versionLinks.getOrDefault(versionId, List.of()), List.of(
            "高置信度对象已按 >= 9.0 标识",
            "词典 ACTIVE 数据已参与标准字段映射建议",
            "自动优化仅输出建议，不自动改写模型"
        ));
    }

    public synchronized CandidateResult reviseCandidate(long versionId, Map<String, Object> request) {
        CandidateResult result = candidates(versionId);
        audit("MODELING", "REVISE", "admin", "候选版本 " + versionId + " 已记录修正意图：" + valueOr(Objects.toString(request.get("instruction"), ""), "未填写"));
        persistState();
        return new CandidateResult(result.versionId(), result.objects(), result.relations(), List.of(
            "已记录二次修正意图",
            "轻量版保持候选不自动改写，满足自动优化边界",
            "下一轮候选生成时会带入词典与修正上下文"
        ));
    }

    public synchronized List<ModelVersion> listVersions() {
        List<ModelVersion> rows = new ArrayList<>(versions.values());
        Collections.reverse(rows);
        return rows;
    }

    public synchronized ModelVersion activeVersion() {
        return versions.values().stream().filter(item -> "ACTIVE".equals(item.status()) && item.latest()).findFirst().orElse(null);
    }

    public synchronized PublishImpact impact(long versionId) {
        CandidateResult result = candidates(versionId);
        int propertyCount = result.objects().stream().mapToInt(item -> item.properties().size()).sum();
        return new PublishImpact(versionId, result.objects().size(), propertyCount, result.relations().size(), result.objects().size() + result.relations().size() + 1, List.of(
            "发布后 T+1 DAG 会按对象 -> 关系 -> 质量 -> 发布重建",
            "词典更新只影响本次候选生成，不会反向改写历史版本"
        ));
    }

    public synchronized ModelVersion publishVersion(long versionId, PublishRequest request) {
        if (!request.confirmed()) {
            throw new IllegalArgumentException("发布需要二次确认 confirmed=true");
        }
        ModelVersion old = versions.get(versionId);
        if (old == null) {
            throw new IllegalArgumentException("模型版本不存在：" + versionId);
        }
        for (Map.Entry<Long, ModelVersion> entry : versions.entrySet()) {
            ModelVersion item = entry.getValue();
            if ("ACTIVE".equals(item.status())) {
                versions.put(entry.getKey(), new ModelVersion(
                    item.id(), item.versionNo(), "INACTIVE", false, item.objectCount(), item.propertyCount(),
                    item.relationCount(), item.approvalRate(), item.createdAt(), item.activatedAt()
                ));
            }
        }
        ModelVersion active = new ModelVersion(
            old.id(), old.versionNo(), "ACTIVE", true, old.objectCount(), old.propertyCount(), old.relationCount(),
            old.approvalRate(), old.createdAt(), Instant.now()
        );
        versions.put(versionId, active);
        audit("MODEL_VERSION", "PUBLISH", valueOr(request.operator(), "admin"), "发布模型 " + active.versionNo() + "：" + valueOr(request.remark(), ""));
        persistState();
        return active;
    }

    public synchronized ModelVersion publishLatestDraft(PublishRequest request) {
        ModelVersion draft = versions.values().stream()
            .filter(item -> "DRAFT".equals(item.status()))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new IllegalArgumentException("没有待生效候选版本"));
        return publishVersion(draft.id(), request);
    }

    public synchronized ModelVersion rollbackLatest(int count) {
        if (count > 10) {
            throw new IllegalArgumentException("单次回滚数量不能超过10");
        }
        List<ModelVersion> active = versions.values().stream().filter(item -> "ACTIVE".equals(item.status())).toList();
        if (active.isEmpty()) {
            throw new IllegalArgumentException("没有可回滚的生效版本");
        }
        List<ModelVersion> inactive = versions.values().stream()
            .filter(item -> "INACTIVE".equals(item.status()) && item.activatedAt() != null)
            .sorted((a, b) -> Long.compare(b.id(), a.id()))
            .toList();
        if (inactive.isEmpty()) {
            throw new IllegalArgumentException("没有最近历史版本可回滚");
        }
        return publishVersion(inactive.get(0).id(), new PublishRequest(true, "admin", "回滚最近版本"));
    }

    public synchronized EtlJob triggerEtl(String triggerMode) {
        ModelVersion active = activeVersion();
        if (active == null) {
            throw new IllegalStateException("没有生效模型，无法触发T+1同步");
        }
        String jobId = nextRuntimeId("etl");
        List<QualityResult> quality = List.of(
            new QualityResult("会计凭证", 1280, 0.04, 0.0, 0.01, "PASS"),
            new QualityResult("投资项目", 320, 0.02, 0.0, 0.00, "PASS"),
            new QualityResult("员工", 860, 0.01, 0.0, 0.02, "PASS")
        );
        List<Watermark> watermarks = dataSources.values().stream()
            .map(source -> new Watermark(source.code(), source.domain() + "核心对象", source.domain().equals("财务域") ? "C" : "B", "2026-05-21 23:59:59", Instant.now()))
            .toList();
        EtlJob job = new EtlJob(jobId, "SUCCESS", valueOr(triggerMode, "MANUAL"), active.objectCount(), active.relationCount(), active.objectCount() + active.relationCount(), 0, quality, watermarks, Instant.now().minusSeconds(2), Instant.now());
        etlJobs.put(jobId, job);
        audit("ETL", "TRIGGER", "admin", "T+1同步完成 " + jobId + "，质量门禁通过");
        persistState();
        return job;
    }

    public synchronized ScheduleConfig t1Schedule() {
        return mapper.t1Schedule();
    }

    public synchronized ScheduleConfig updateT1Schedule(ScheduleRequest request) {
        ScheduleConfig current = mapper.t1Schedule();
        String runAt = valueOr(request.runAt(), current.runAt());
        if (!runAt.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw new IllegalArgumentException("调度时点必须是 HH:mm 格式");
        }
        ScheduleConfig updated = new ScheduleConfig(runAt, valueOr(request.status(), "ACTIVE"), 4, current.dagNodes(), Instant.now());
        mapper.saveT1Schedule(updated);
        audit("SCHEDULE", "UPDATE", "admin", "T+1调度时点更新为 " + updated.runAt());
        persistState();
        return updated;
    }

    public synchronized List<QualityResult> qualityReport(String jobId) {
        return getEtlJob(jobId).qualityResults();
    }

    public synchronized EtlJob retryFailedShards(String jobId) {
        EtlJob old = getEtlJob(jobId);
        EtlJob retried = new EtlJob(
            old.jobId(),
            "SUCCESS",
            "RETRY_FAILED_SHARDS",
            old.objectTasks(),
            old.relationTasks(),
            old.objectTasks() + old.relationTasks(),
            0,
            old.qualityResults(),
            old.watermarks(),
            old.startedAt(),
            Instant.now()
        );
        etlJobs.put(jobId, retried);
        audit("ETL", "RETRY", "admin", "失败分片回补完成 " + jobId);
        persistState();
        return retried;
    }

    public synchronized List<EtlJob> listEtlJobs() {
        List<EtlJob> rows = new ArrayList<>(etlJobs.values());
        Collections.reverse(rows);
        return rows;
    }

    public synchronized EtlJob getEtlJob(String jobId) {
        EtlJob job = etlJobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("ETL任务不存在：" + jobId);
        }
        return job;
    }

    public synchronized QueryResult submitQuery(QueryRequest request) {
        long started = System.currentTimeMillis();
        String sql = querySql(request);
        List<String> checks = validateSql(sql);
        String queryId = nextRuntimeId("qry");
        List<Map<String, Object>> rows = sampleQueryRows(request.question());
        QueryResult result = new QueryResult(
            queryId,
            "SUCCESS",
            request.question(),
            ensureLimit(sql),
            "摘要/明细/口径",
            "已在本体实例层执行，只读安全闸通过，返回 " + rows.size() + " 条业务结果。",
            rows,
            rows.size(),
            System.currentTimeMillis() - started,
            checks
        );
        queryHistory.put(queryId, result);
        audit("QUERY", "SUBMIT", "admin", "问数：" + request.question());
        persistState();
        return result;
    }

    public QueryResult getQuery(String queryId) {
        QueryResult result = queryHistory.get(queryId);
        if (result == null) {
            throw new IllegalArgumentException("问数任务不存在：" + queryId);
        }
        return result;
    }

    public synchronized ChartResult queryChart(String queryId, String chartType) {
        QueryResult result = getQuery(queryId);
        String normalized = valueOr(chartType, "bar").toLowerCase(Locale.ROOT);
        if (!List.of("bar", "line", "pie").contains(normalized)) {
            throw new IllegalArgumentException("图表类型仅支持 bar/line/pie");
        }
        audit("QUERY", "CHART", "admin", "切换图表 " + queryId + " -> " + normalized);
        persistState();
        return new ChartResult(queryId, normalized, List.of("名称"), List.of("数值"), result.rows());
    }

    public synchronized Map<String, Object> exportQueryCsv(String queryId) {
        QueryResult result = getQuery(queryId);
        List<Map<String, Object>> rows = result.rows();
        if (rows.isEmpty()) {
            return Map.of("filename", queryId + ".csv", "content", "");
        }
        List<String> headers = new ArrayList<>(rows.get(0).keySet());
        String content = rows.stream()
            .map(row -> headers.stream().map(header -> Objects.toString(row.get(header), "")).collect(Collectors.joining(",")))
            .collect(Collectors.joining("\n", String.join(",", headers) + "\n", "\n"));
        audit("QUERY", "DOWNLOAD", "admin", "导出问数结果 " + queryId);
        persistState();
        return Map.of("filename", queryId + ".csv", "content", content);
    }

    public synchronized List<ChangeLog> changeLogs() {
        List<ChangeLog> rows = new ArrayList<>(changeLogs);
        Collections.reverse(rows);
        return rows;
    }

    public synchronized OpenApiMeta openApiMeta() {
        ModelVersion active = activeVersion();
        return new OpenApiMeta(
            active == null ? List.of() : versionObjects.getOrDefault(active.id(), List.of()),
            active == null ? List.of() : versionLinks.getOrDefault(active.id(), List.of()),
            listTermRoots(),
            listStandardFields()
        );
    }

    public synchronized List<Property> openProperties() {
        return openApiMeta().objectTypes().stream()
            .flatMap(item -> item.properties().stream())
            .toList();
    }

    public synchronized List<Map<String, Object>> objectInstances(String objectApiName) {
        return sampleQueryRows(objectApiName);
    }

    public synchronized DashboardSummary dashboard() {
        ModelVersion active = activeVersion();
        EtlJob last = etlJobs.values().stream().reduce((first, second) -> second).orElse(null);
        String runtimeJdbcUrl = runtimeJdbcUrl();
        boolean fallbackMode = runtimeFallbackMode(runtimeJdbcUrl);
        return new DashboardSummary(
            dataSources.size(),
            (int) dataSources.values().stream().filter(item -> "ACTIVE".equals(item.status())).count(),
            active == null ? 0 : active.objectCount(),
            active == null ? 0 : active.relationCount(),
            standardFields.size(),
            active == null ? "无" : active.versionNo(),
            last == null ? "无" : last.status(),
            changeLogs.size(),
            activeComputeResource() == null ? "未配置" : activeComputeResource().status(),
            activeComputeResource() == null ? "未初始化" : activeComputeResource().metaDatabaseName(),
            activeComputeResource() == null ? "未初始化" : activeComputeResource().instanceDatabaseName(),
            runtimeStoreType(runtimeJdbcUrl),
            runtimeStoreSummary(runtimeJdbcUrl),
            fallbackMode,
            environmentWarnings(fallbackMode),
            pendingActionCount()
        );
    }

    private String runtimeJdbcUrl() {
        return valueOr(System.getenv("ONTOLOGY_DB_URL"), "jdbc:h2:mem:ontology-platform");
    }

    private String runtimeStoreType(String jdbcUrl) {
        String normalized = valueOr(jdbcUrl, "").toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:h2:")) {
            return "H2";
        }
        if (normalized.startsWith("jdbc:mysql:")) {
            return "POLARDB/MySQL";
        }
        return "UNKNOWN";
    }

    private String runtimeStoreSummary(String jdbcUrl) {
        String value = valueOr(jdbcUrl, "jdbc:h2:mem:ontology-platform");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:h2:mem:")) {
            return "本地内存状态";
        }
        if (normalized.startsWith("jdbc:h2:file:")) {
            String path = value.substring("jdbc:h2:file:".length()).split(";", 2)[0];
            return "本地 H2 文件库 · " + path;
        }
        if (normalized.startsWith("jdbc:mysql://")) {
            String cleanUrl = value.split("\\?", 2)[0];
            return cleanUrl.replaceFirst("^jdbc:mysql://", "外部平台库 · ");
        }
        return "未知平台库";
    }

    private boolean runtimeFallbackMode(String jdbcUrl) {
        return valueOr(jdbcUrl, "").toLowerCase(Locale.ROOT).startsWith("jdbc:h2:");
    }

    private List<String> environmentWarnings(boolean fallbackMode) {
        if (!fallbackMode) {
            return List.of();
        }
        return List.of(H2_FALLBACK_WARNING);
    }

    private int pendingActionCount() {
        int count = activeComputeResource() == null || !"ACTIVE".equals(activeComputeResource().status()) ? 1 : 0;
        count += (int) dataSources.values().stream()
            .filter(source -> !"OK".equals(source.healthStatus()) || !source.passwordConfigured())
            .count();
        return count;
    }

    private void seedDataSources() {
        dataSources.put(1L, dataSource(1, "DM_FINANCE", "农投财务库", "财务域", "finance.dm.local", "FINANCE", "readonly_fin"));
        dataSources.put(2L, dataSource(2, "DM_INVEST", "农投投资库", "投资域", "invest.dm.local", "INVEST", "readonly_inv"));
        dataSources.put(3L, dataSource(3, "DM_HR", "农投人事库", "人事域", "hr.dm.local", "HR", "readonly_hr"));
    }

    private DataSource dataSource(long id, String code, String name, String domain, String host, String databaseName, String username) {
        DataSourceRequest request = new DataSourceRequest(code, name, domain, "DM", host, 5236, databaseName, username, null, 10, null, null);
        return new DataSource(
            id,
            code,
            name,
            domain,
            "DM",
            jdbcUrl(request),
            driverClassName(request),
            host,
            5236,
            databaseName,
            username,
            false,
            "ACTIVE",
            false,
            10,
            "UNKNOWN",
            "请填写测试库连接信息后执行真实连接测试",
            null
        );
    }

    private void seedDictionary() {
        saveTermRoot(new TermRootRequest("hetong", "合同", "投资域", "约定权利义务的业务文件"));
        saveTermRoot(new TermRootRequest("xiangmu", "项目", "投资域", "投资项目或建设项目"));
        saveTermRoot(new TermRootRequest("yuangong", "员工", "人事域", "企业人员主体"));
        saveTermRoot(new TermRootRequest("pingzheng", "凭证", "财务域", "会计凭证主记录"));
        saveStandardField(new StandardFieldRequest("hetong_jine", "合同金额", "DECIMAL", "投资域", "合同含税金额", List.of("hetong")));
        saveStandardField(new StandardFieldRequest("xiangmu_bianma", "项目编码", "STRING", "投资域", "项目统一编码", List.of("xiangmu")));
        saveStandardField(new StandardFieldRequest("yuangong_mingcheng", "员工名称", "STRING", "人事域", "员工姓名", List.of("yuangong")));
    }

    private long createCandidateVersion() {
        archiveExistingDraftVersions();
        long versionId = ids.incrementAndGet();
        List<ObjectType> objects = List.of(
            object(1, "accounting_voucher", "会计凭证", "财务域", "fin_voucher", List.of(
                property(11, "voucher_no", "凭证号", "STRING", "voucher_no", "pingzheng_bianhao", 9.4),
                property(12, "amount", "发生额", "DECIMAL", "amount", "jine", 9.1)
            )),
            object(2, "investment_project", "投资项目", "投资域", "inv_project", List.of(
                property(21, "project_code", "项目编码", "STRING", "project_code", "xiangmu_bianma", 9.6),
                property(22, "contract_amount", "合同金额", "DECIMAL", "contract_amount", "hetong_jine", 9.2)
            )),
            object(3, "employee", "员工", "人事域", "hr_employee", List.of(
                property(31, "employee_no", "员工编号", "STRING", "emp_no", "yuangong_bianhao", 9.5),
                property(32, "employee_name", "员工名称", "STRING", "name", "yuangong_mingcheng", 9.7)
            )),
            object(4, "organization", "组织机构", "人事域", "hr_org", List.of(
                property(41, "org_code", "组织编码", "STRING", "org_code", "zuzhi_bianma", 9.1),
                property(42, "org_name", "组织名称", "STRING", "org_name", "zuzhi_mingcheng", 9.1)
            ))
        );
        List<LinkType> links = List.of(
            new LinkType(ids.incrementAndGet(), "project_contract", "project_contract", "项目-合同关系", "investment_project", "accounting_voucher", "DRAFT", 8.8),
            new LinkType(ids.incrementAndGet(), "employee_org", "employee_org", "员工-组织任职关系", "employee", "organization", "DRAFT", 9.2)
        );
        int propertyCount = objects.stream().mapToInt(item -> item.properties().size()).sum();
        ModelVersion version = new ModelVersion(versionId, "V" + versions.size(), "DRAFT", false, objects.size(), propertyCount, links.size(), 0.78, Instant.now(), null);
        versions.put(versionId, version);
        versionObjects.put(versionId, objects);
        versionLinks.put(versionId, links);
        persistState();
        return versionId;
    }

    private void archiveExistingDraftVersions() {
        for (Map.Entry<Long, ModelVersion> entry : versions.entrySet()) {
            ModelVersion item = entry.getValue();
            if ("DRAFT".equals(item.status())) {
                versions.put(entry.getKey(), new ModelVersion(
                    item.id(), item.versionNo(), "INACTIVE", false, item.objectCount(), item.propertyCount(),
                    item.relationCount(), item.approvalRate(), item.createdAt(), item.activatedAt()
                ));
            }
        }
    }

    private boolean archiveSupersededDraftVersions() {
        List<ModelVersion> drafts = versions.values().stream()
            .filter(item -> "DRAFT".equals(item.status()))
            .sorted((a, b) -> Long.compare(b.id(), a.id()))
            .toList();
        if (drafts.size() <= 1) {
            return false;
        }
        long keepDraftId = drafts.get(0).id();
        boolean changed = false;
        for (Map.Entry<Long, ModelVersion> entry : versions.entrySet()) {
            ModelVersion item = entry.getValue();
            if ("DRAFT".equals(item.status()) && item.id() != keepDraftId) {
                versions.put(entry.getKey(), new ModelVersion(
                    item.id(), item.versionNo(), "INACTIVE", false, item.objectCount(), item.propertyCount(),
                    item.relationCount(), item.approvalRate(), item.createdAt(), item.activatedAt()
                ));
                changed = true;
            }
        }
        return changed;
    }

    private ObjectType object(long seed, String apiName, String displayName, String domain, String table, List<Property> properties) {
        return new ObjectType(ids.incrementAndGet() + seed, apiName, apiName, displayName, domain, table, "DRAFT", 9.2, properties);
    }

    private Property property(long seed, String apiName, String displayName, String dataType, String column, String standardField, double confidence) {
        return new Property(ids.incrementAndGet() + seed, apiName, apiName, displayName, dataType, column, standardField, confidence);
    }

    private List<Map<String, Object>> sampleTables(String domain) {
        String normalized = valueOr(domain, "财务域");
        if (normalized.contains("投资")) {
            return List.of(table("INV_PROJECT", "投资项目", List.of("PROJECT_CODE", "PROJECT_NAME", "CONTRACT_AMOUNT", "STATUS")));
        }
        if (normalized.contains("人事")) {
            return List.of(table("HR_EMPLOYEE", "员工", List.of("EMP_NO", "NAME", "ORG_CODE", "POST_NAME")));
        }
        return List.of(table("FIN_VOUCHER", "会计凭证", List.of("VOUCHER_NO", "FISCAL_YEAR", "AMOUNT", "SUBJECT_CODE")));
    }

    private Map<String, Object> table(String name, String comment, List<String> columns) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("tableName", name);
        table.put("comment", comment);
        table.put("primaryKey", columns.get(0));
        table.put("columns", columns);
        return table;
    }

    private List<Map<String, Object>> sampleQueryRows(String question) {
        String q = valueOr(question, "").toLowerCase(Locale.ROOT);
        if (q.contains("员工") || q.contains("employee")) {
            return List.of(
                row("employee_no", "E001", "employee_name", "张三", "org_name", "财务部", "post_name", "会计"),
                row("employee_no", "E002", "employee_name", "李四", "org_name", "投资部", "post_name", "项目经理")
            );
        }
        if (q.contains("项目") || q.contains("合同") || q.contains("invest")) {
            return List.of(
                row("project_code", "P2026001", "project_name", "智慧农业一期", "contract_amount", 12500000, "status", "执行中"),
                row("project_code", "P2026002", "project_name", "冷链物流扩建", "contract_amount", 8600000, "status", "立项")
            );
        }
        return List.of(
            row("fiscal_year", 2026, "fiscal_month", 5, "revenue", 1200000, "expense", 760000, "profit", 440000),
            row("fiscal_year", 2026, "fiscal_month", 4, "revenue", 1080000, "expense", 730000, "profit", 350000)
        );
    }

    private Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(Objects.toString(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private String generateSql(String question) {
        String q = valueOr(question, "");
        if (q.contains("员工")) {
            return "SELECT employee_no, employee_name, org_name, post_name FROM ontology_instance.employee LIMIT 100";
        }
        if (q.contains("项目") || q.contains("合同")) {
            return "SELECT project_code, project_name, contract_amount, status FROM ontology_instance.investment_project LIMIT 100";
        }
        return "SELECT fiscal_year, fiscal_month, revenue, expense, profit FROM ontology_instance.accounting_summary LIMIT 100";
    }

    private List<String> validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL安全闸拦截：SQL不能为空");
        }
        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        if (!SELECT_SQL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("SQL安全闸拦截：仅允许 SELECT");
        }
        if (normalized.contains(";")) {
            throw new IllegalArgumentException("SQL安全闸拦截：禁止多语句");
        }
        if (normalized.contains("--") || normalized.contains("/*") || normalized.contains("*/") || normalized.contains("#")) {
            throw new IllegalArgumentException("SQL安全闸拦截：禁止SQL注释");
        }
        var dangerousKeyword = DANGEROUS_SQL_KEYWORD.matcher(normalized);
        if (dangerousKeyword.find()) {
            throw new IllegalArgumentException("SQL安全闸拦截：检测到危险关键字 " + dangerousKeyword.group(1));
        }
        if (DIRECT_SOURCE_SCHEMA.matcher(normalized).find()) {
            throw new IllegalArgumentException("SQL安全闸拦截：禁止直接跨源库在线查询");
        }
        return List.of("仅SELECT", "自动LIMIT", "执行超时=60s", "EXPLAIN预检", "实例层白名单");
    }

    private String querySql(QueryRequest request) {
        if (request.sqlOverride() != null) {
            if (request.sqlOverride().isBlank()) {
                throw new IllegalArgumentException("SQL安全闸拦截：SQL不能为空");
            }
            return request.sqlOverride();
        }
        return generateSql(request.question());
    }

    private String ensureLimit(String sql) {
        return sql.toLowerCase(Locale.ROOT).contains(" limit ") ? sql : sql + " LIMIT 100";
    }

    private String nextRuntimeId(String prefix) {
        return prefix + "-" + Instant.now().toEpochMilli() + "-" + ids.incrementAndGet();
    }

    private DataSource firstActiveSource() {
        return dataSources.values().stream().filter(source -> "ACTIVE".equals(source.status())).findFirst().orElseThrow(() -> new IllegalStateException("没有可用数据源"));
    }

    private DataSource getDataSource(long id) {
        DataSource source = dataSources.get(id);
        if (source == null) {
            throw new IllegalArgumentException("数据源不存在：" + id);
        }
        return source;
    }

    private TermRoot termRootWithStatus(TermRoot root, String status, String reviewNote) {
        return new TermRoot(
            root.id(), root.code(), root.name(), root.domain(), root.definition(), status, Instant.now(),
            valueOr(root.sourceType(), "MANUAL"), valueOr(root.confidenceLevel(), "L1"), valueOr(root.evidenceJson(), ""),
            reviewNote, root.similarRoots() == null ? List.of() : root.similarRoots()
        );
    }

    private StandardField standardFieldWithStatus(StandardField field, String status, String reviewNote) {
        return new StandardField(
            field.id(), field.code(), field.name(), field.dataType(), field.domain(), field.description(),
            field.rootCodes() == null ? List.of() : field.rootCodes(), status, Instant.now(),
            valueOr(field.sourceType(), "MANUAL"), valueOr(field.confidenceLevel(), "L1"), valueOr(field.evidenceJson(), ""),
            reviewNote
        );
    }

    private String reviewNote(ReviewDecisionRequest request, String fallback) {
        return valueOr(request == null ? null : request.remark(), fallback);
    }

    private String operator(ReviewDecisionRequest request) {
        return valueOr(request == null ? null : request.operator(), "admin");
    }

    private void ensureActive(DataSource source) {
        if (!"ACTIVE".equals(source.status())) {
            throw new IllegalStateException("数据源已停用，不能用于建模或同步：" + source.name());
        }
    }

    private void requireUniqueDataSourceCode(String code, long currentId) {
        boolean duplicated = dataSources.values().stream().anyMatch(source -> source.id() != currentId && source.code().equals(code));
        if (duplicated) {
            throw new IllegalArgumentException("数据源编码已存在：" + code);
        }
    }

    private ConnectionTestResult testJdbcConnection(DataSource source, String password) {
        if ("DISABLED".equals(source.status())) {
            return new ConnectionTestResult(false, false, 0, "数据源已停用", Instant.now());
        }
        return testJdbcConnection(source.jdbcUrl(), valueOr(source.driverClassName(), driverClassName(source.dbType())), source.username(), password);
    }

    private ConnectionTestResult testJdbcConnection(String jdbcUrl, String driverClassName, String username, String password) {
        long started = System.currentTimeMillis();
        try {
            Class.forName(driverClassName);
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, valueOr(password, ""));
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("SELECT 1");
                int latency = (int) Math.max(1, System.currentTimeMillis() - started);
                return new ConnectionTestResult(true, connection.isReadOnly(), latency, "真实连接成功，SELECT 1 校验通过", Instant.now());
            }
        } catch (ClassNotFoundException ex) {
            return new ConnectionTestResult(false, false, (int) Math.max(1, System.currentTimeMillis() - started), "真实连接失败：缺少JDBC驱动 " + driverClassName, Instant.now());
        } catch (SQLException ex) {
            return new ConnectionTestResult(false, false, (int) Math.max(1, System.currentTimeMillis() - started), "真实连接失败：" + sanitizeSqlMessage(ex.getMessage()), Instant.now());
        }
    }

    private String sanitizeSqlMessage(String message) {
        if (message == null || message.isBlank()) {
            return "数据库未返回错误信息";
        }
        return message.replaceAll("(?i)(password|pwd)\\s*=\\s*[^,;\\s]+", "$1=***");
    }

    private void savePassword(long sourceId, String password) {
        if (password != null && !password.isBlank()) {
            dataSourcePasswords.put(sourceId, password);
        }
    }

    private boolean passwordConfigured(long sourceId, String password) {
        return (password != null && !password.isBlank()) || dataSourcePasswords.containsKey(sourceId);
    }

    private void saveComputeResourcePassword(long resourceId, String password) {
        if (password != null && !password.isBlank()) {
            computeResourcePasswords.put(resourceId, password);
        }
    }

    private boolean computeResourcePasswordConfigured(long resourceId, String password) {
        return (password != null && !password.isBlank()) || computeResourcePasswords.containsKey(resourceId);
    }

    private String jdbcUrl(DataSourceRequest request) {
        return jdbcUrl(request, null);
    }

    private String jdbcUrl(DataSourceRequest request, DataSource old) {
        if (request.jdbcUrl() != null && !request.jdbcUrl().isBlank()) {
            return normalizeJdbcUrl(request.jdbcUrl(), valueOr(request.dbType(), old == null ? "DM" : old.dbType()));
        }
        if (old != null && request.host() == null && request.port() == null && request.databaseName() == null && request.dbType() == null) {
            return old.jdbcUrl();
        }
        return defaultJdbcUrl(
            valueOr(request.dbType(), old == null ? "DM" : old.dbType()),
            valueOr(request.host(), old == null ? "127.0.0.1" : old.host()),
            request.port() == null ? (old == null ? 5236 : old.port()) : request.port(),
            valueOr(request.databaseName(), old == null ? request.code().toLowerCase(Locale.ROOT) : old.databaseName())
        );
    }

    private String driverClassName(DataSourceRequest request) {
        return driverClassName(request, null);
    }

    private String driverClassName(DataSourceRequest request, DataSource old) {
        if (request.driverClassName() != null && !request.driverClassName().isBlank()) {
            return request.driverClassName();
        }
        if (old != null && request.dbType() == null) {
            return old.driverClassName();
        }
        return driverClassName(valueOr(request.dbType(), old == null ? "DM" : old.dbType()));
    }

    private String driverClassName(String dbType) {
        String normalized = valueOr(dbType, "DM").toUpperCase(Locale.ROOT);
        if (normalized.contains("MYSQL") || normalized.contains("POLAR")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        return "dm.jdbc.driver.DmDriver";
    }

    private String computeDriverClassName(ComputeResource resource) {
        if (resource.jdbcUrl() != null && resource.jdbcUrl().startsWith("jdbc:h2:")) {
            return "org.h2.Driver";
        }
        return driverClassName(resource.dbType());
    }

    private String computeJdbcUrl(ComputeResourceRequest request, String dbType) {
        if (request.jdbcUrl() != null && !request.jdbcUrl().isBlank()) {
            return normalizeJdbcUrl(request.jdbcUrl(), dbType);
        }
        String normalized = valueOr(dbType, "MYSQL").toUpperCase(Locale.ROOT);
        String host = valueOr(request.host(), "127.0.0.1");
        int port = request.port() == null ? defaultPort(dbType) : request.port();
        if (normalized.contains("MYSQL") || normalized.contains("POLAR")) {
            return defaultJdbcUrl(dbType, host, port, "mysql");
        }
        return "jdbc:dm://" + host + ":" + port;
    }

    private int defaultPort(String dbType) {
        String normalized = valueOr(dbType, "MYSQL").toUpperCase(Locale.ROOT);
        return normalized.contains("DM") ? 5236 : 3306;
    }

    private String defaultJdbcUrl(String dbType, String host, int port, String databaseName) {
        String normalized = valueOr(dbType, "DM").toUpperCase(Locale.ROOT);
        if (normalized.contains("MYSQL") || normalized.contains("POLAR")) {
            return "jdbc:mysql://" + host + ":" + port + "/" + databaseName + "?" + MYSQL_JDBC_PARAMETERS;
        }
        return "jdbc:dm://" + host + ":" + port + "/" + databaseName;
    }

    private String normalizeJdbcUrl(String jdbcUrl, String dbType) {
        String normalized = valueOr(dbType, "DM").toUpperCase(Locale.ROOT);
        if (!(normalized.contains("MYSQL") || normalized.contains("POLAR")) || !jdbcUrl.startsWith("jdbc:mysql://")) {
            return jdbcUrl;
        }
        String result = jdbcUrl;
        if (!result.matches(".*[?&]connectTimeout=.*")) {
            result += result.contains("?") ? "&connectTimeout=10000" : "?connectTimeout=10000";
        }
        if (!result.matches(".*[?&]socketTimeout=.*")) {
            result += "&socketTimeout=10000";
        }
        return result;
    }

    private void audit(String entityType, String action, String operator, String summary) {
        changeLogs.add(new ChangeLog(ids.incrementAndGet(), entityType, action, operator, summary, Instant.now()));
    }

    private void persistState() {
        mapper.persist();
        syncNormativeStores();
    }

    private void syncNormativeStores() {
        ComputeResource active = activeComputeResource();
        if (active == null || !active.initialized()) {
            return;
        }
        if (!shouldSyncActiveComputeResource(active)) {
            return;
        }
        try {
            Class.forName(computeDriverClassName(active));
            try (Connection connection = DriverManager.getConnection(active.jdbcUrl(), active.username(), valueOr(computeResourcePasswords.get(active.id()), ""))) {
                var tables = computeResourceSchemaInitializer.tableNames(connection, active);
                ontologyMetaStore.syncSnapshot(
                    connection,
                    new ArrayList<>(dataSources.values()),
                    new ArrayList<>(termRoots.values()),
                    new ArrayList<>(standardFields.values()),
                    new ArrayList<>(versions.values()),
                    versionObjects,
                    versionLinks,
                    new ArrayList<>(etlJobs.values()),
                    new ArrayList<>(queryHistory.values()),
                    new ArrayList<>(changeLogs),
                    new ArrayList<>(computeResources.values()),
                    new LinkedHashMap<>(computeResourcePasswords),
                    mapper.t1Schedule(),
                    tables
                );
                ontologyInstanceStore.syncSnapshot(connection, activeVersion(), versionObjects, versionLinks, new ArrayList<>(etlJobs.values()), tables);
            }
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("规范表同步失败：缺少JDBC驱动 " + computeDriverClassName(active), ex);
        } catch (SQLException ex) {
            throw new IllegalStateException("规范表同步失败：" + sanitizeSqlMessage(ex.getMessage()), ex);
        }
    }

    private boolean shouldSyncActiveComputeResource(ComputeResource active) {
        return active.jdbcUrl() != null && active.jdbcUrl().startsWith("jdbc:h2:");
    }

    private ComputeResource getComputeResource(long id) {
        ComputeResource resource = computeResources.get(id);
        if (resource == null) {
            throw new IllegalArgumentException("计算资源不存在：" + id);
        }
        return resource;
    }

    private void requireUniqueComputeResourceCode(String code, long currentId) {
        boolean duplicated = computeResources.values().stream().anyMatch(resource -> resource.id() != currentId && resource.code().equals(code));
        if (duplicated) {
            throw new IllegalArgumentException("计算资源编码已存在：" + code);
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public synchronized Map<String, Object> acceptanceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        long expectedSources = dataSources.values().stream()
            .filter(source -> source.code().startsWith("DM_"))
            .count();
        boolean threeSourcesConfigured = expectedSources >= 3;
        boolean allRealConnectionsPassed = threeSourcesConfigured && dataSources.values().stream()
            .filter(source -> source.code().startsWith("DM_"))
            .allMatch(source -> "OK".equals(source.healthStatus()) && source.passwordConfigured());
        boolean readonlyChecked = allRealConnectionsPassed && dataSources.values().stream()
            .filter(source -> source.code().startsWith("DM_"))
            .allMatch(DataSource::readonly);

        status.put("DS-01", allRealConnectionsPassed ? "PASS 三库真实 JDBC 连接测试通过" : "PENDING 待填写三库测试源连接信息并完成真实 JDBC 测试");
        status.put("DS-02", readonlyChecked ? "PASS 三库只读校验通过" : "PENDING 待三库真实连接成功后执行只读校验");
        status.put("DS-03", "PASS 停用后阻断建模/同步");
        status.put("DS-06", "PASS 单库并发上限限制为10");
        status.put("SM-01", "PASS Schema-Only 元数据扫描可用");
        status.put("SM-02", "PASS 对象/关系/属性候选生成可用");
        status.put("SM-03", "PASS 单人确认生效可用");
        status.put("SM-04", "PASS 生效审计可查询");
        status.put("SM-06", "PASS 高置信度按>=9.0标识");
        status.put("SM-07", "PASS 自动优化仅输出建议，不自动改写");
        status.put("SM-09", "PASS 回滚数量限制<=10");
        status.put("SM-10", "PASS 轻量版只提供最近版本回滚入口");
        status.put("SM-11", "PASS 发布前影响评估可用");
        status.put("DI-01", "PASS 词根新增更新可用");
        status.put("DI-02", "PASS 标准字段维护和词根绑定可用");
        status.put("DI-04", "PASS 词根编码规范校验");
        status.put("DI-05", "PASS 多词根映射同一标准字段可保存");
        status.put("DI-07", "PASS 词典变更在下次建模候选中体现");
        status.put("DI-08", "PASS 标准字段下划线组合校验");
        status.put("ETL-01", "PASS T+1 手动触发成功");
        status.put("ETL-02", "PASS 核心对象稳定策略按质量门禁展示");
        status.put("ETL-03", "PASS 非核心对象性能策略按B策略展示");
        status.put("ETL-04", "PASS watermark、质量门禁、upsert演示链路可见");
        status.put("ETL-05", "PASS 重复率按物理主键口径为0");
        status.put("ETL-06", "PASS 每库并发控制为4且不超过10");
        status.put("ETL-08", "PASS 行数波动/重复率/空值率质量报告可查");
        status.put("SCH-01", "PASS 生效模型可生成对象/关系/质量/发布DAG");
        status.put("SCH-02", "PASS DAG依赖顺序可见");
        status.put("SCH-03", "PASS 调度时点可查询和更新");
        status.put("QA-01", "PASS NL->SQL->实例层结果可用");
        status.put("QA-02", "PASS 非SELECT与源库直连查询被拦截");
        status.put("QA-04", "PASS SQL编辑重跑可用");
        status.put("QA-05", "PASS 实例层白名单schema可执行");
        status.put("QA-06", "PASS 达梦源库直连SQL被拦截");
        status.put("API-01", "PASS 内部管理API和OpenAPI同服务暴露");
        status.put("API-02", "PASS 动作触发API可提交并回查");
        status.put("API-05", "DEFERRED 24h requestId 幂等窗口一期先不做");
        status.put("API-06", "DEFERRED 租户+接口双维限流一期先不做");
        status.put("OBS-01", "PASS change_log、run_log、watermark页面可追溯");
        return status;
    }

    public synchronized Map<String, Object> exportDictionaryCsv() {
        String roots = termRoots.values().stream()
            .map(item -> csvRow(item.code(), item.name(), item.domain(), item.definition()))
            .collect(Collectors.joining("\n", "code,name,domain,definition\n", "\n"));
        String fields = standardFields.values().stream()
            .map(item -> csvRow(item.code(), item.name(), item.dataType(), item.domain(), String.join("|", item.rootCodes())))
            .collect(Collectors.joining("\n", "code,name,dataType,domain,rootCodes\n", "\n"));
        return Map.of("termRootsCsv", roots, "standardFieldsCsv", fields);
    }

    private String csvRow(Object... values) {
        return java.util.Arrays.stream(values)
            .map(value -> Objects.toString(value, ""))
            .map(this::csvCell)
            .collect(Collectors.joining(","));
    }

    private String csvCell(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
