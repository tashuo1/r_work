package com.ontology.lite.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontology.lite.model.PlatformModels.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

@Primary
@Repository
public class MyBatisOntologyDataMapper implements OntologyDataMapper {
    public static final String STATE_KEY = "ontology-platform";

    private final PersistentPlatformStateMapper stateMapper;
    private final ObjectMapper objectMapper;
    private final AtomicLong ids = new AtomicLong(100);
    private final Map<Long, DataSource> dataSources = new LinkedHashMap<>();
    private final Map<Long, String> dataSourcePasswords = new LinkedHashMap<>();
    private final Map<Long, ComputeResource> computeResources = new LinkedHashMap<>();
    private final Map<Long, String> computeResourcePasswords = new LinkedHashMap<>();
    private final Map<Long, TermRoot> termRoots = new LinkedHashMap<>();
    private final Map<Long, StandardField> standardFields = new LinkedHashMap<>();
    private final Map<Long, ModelVersion> versions = new LinkedHashMap<>();
    private final Map<Long, List<ObjectType>> versionObjects = new LinkedHashMap<>();
    private final Map<Long, List<LinkType>> versionLinks = new LinkedHashMap<>();
    private final Map<String, EtlJob> etlJobs = new LinkedHashMap<>();
    private final Map<String, QueryResult> queryHistory = new ConcurrentHashMap<>();
    private final List<ChangeLog> changeLogs = new ArrayList<>();
    private ScheduleConfig t1Schedule = defaultSchedule();
    private Long activeComputeResourceId;

    public MyBatisOntologyDataMapper(PersistentPlatformStateMapper stateMapper, ObjectMapper objectMapper) {
        this.stateMapper = stateMapper;
        this.objectMapper = objectMapper;
        load();
    }

    @Override
    public AtomicLong ids() {
        return ids;
    }

    @Override
    public Map<Long, DataSource> dataSources() {
        return dataSources;
    }

    @Override
    public Map<Long, String> dataSourcePasswords() {
        return dataSourcePasswords;
    }

    @Override
    public Map<Long, ComputeResource> computeResources() {
        return computeResources;
    }

    @Override
    public Map<Long, String> computeResourcePasswords() {
        return computeResourcePasswords;
    }

    @Override
    public Long activeComputeResourceId() {
        return activeComputeResourceId;
    }

    @Override
    public void saveActiveComputeResourceId(Long id) {
        this.activeComputeResourceId = id;
        persist();
    }

    @Override
    public Map<Long, TermRoot> termRoots() {
        return termRoots;
    }

    @Override
    public Map<Long, StandardField> standardFields() {
        return standardFields;
    }

    @Override
    public Map<Long, ModelVersion> versions() {
        return versions;
    }

    @Override
    public Map<Long, List<ObjectType>> versionObjects() {
        return versionObjects;
    }

    @Override
    public Map<Long, List<LinkType>> versionLinks() {
        return versionLinks;
    }

    @Override
    public Map<String, EtlJob> etlJobs() {
        return etlJobs;
    }

    @Override
    public Map<String, QueryResult> queryHistory() {
        return queryHistory;
    }

    @Override
    public List<ChangeLog> changeLogs() {
        return changeLogs;
    }

    @Override
    public ScheduleConfig t1Schedule() {
        return t1Schedule;
    }

    @Override
    public void saveT1Schedule(ScheduleConfig scheduleConfig) {
        this.t1Schedule = scheduleConfig;
        persist();
    }

    @Override
    public synchronized void persist() {
        try {
            stateMapper.upsertPayload(STATE_KEY, objectMapper.writeValueAsString(snapshot()));
            stateMapper.replaceComputeResources(new ArrayList<>(computeResources.values()), computeResourcePasswords);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("平台状态序列化失败", ex);
        }
    }

    @Override
    public boolean initialized() {
        return !dataSources.isEmpty()
            || !termRoots.isEmpty()
            || !versions.isEmpty()
            || stateMapper.selectPayload(STATE_KEY) != null;
    }

    private void load() {
        String payload = stateMapper.selectPayload(STATE_KEY);
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            OntologyPlatformState state = objectMapper.readValue(payload, OntologyPlatformState.class);
            ids.set(Math.max(state.nextId(), 100));
            putAll(dataSources, normalizeDataSources(state.dataSources()), DataSource::id);
            if (state.dataSourcePasswords() != null) {
                dataSourcePasswords.putAll(state.dataSourcePasswords());
            }
            putAll(computeResources, normalizeComputeResources(state.computeResources()), ComputeResource::id);
            if (state.computeResourcePasswords() != null) {
                computeResourcePasswords.putAll(state.computeResourcePasswords());
            }
            activeComputeResourceId = state.activeComputeResourceId();
            putAll(termRoots, state.termRoots(), TermRoot::id);
            putAll(standardFields, state.standardFields(), StandardField::id);
            putAll(versions, state.versions(), ModelVersion::id);
            versionObjects.putAll(normalizeVersionObjects(state.versionObjects()));
            versionLinks.putAll(normalizeVersionLinks(state.versionLinks()));
            putAll(etlJobs, state.etlJobs(), EtlJob::jobId);
            putAll(queryHistory, state.queryHistory(), QueryResult::queryId);
            if (state.changeLogs() != null) {
                changeLogs.addAll(state.changeLogs());
            }
            t1Schedule = state.t1Schedule() == null ? defaultSchedule() : state.t1Schedule();
            stateMapper.replaceComputeResources(new ArrayList<>(computeResources.values()), computeResourcePasswords);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("平台状态反序列化失败", ex);
        }
    }

    private OntologyPlatformState snapshot() {
        return new OntologyPlatformState(
            ids.get(),
            new ArrayList<>(dataSources.values()),
            new LinkedHashMap<>(dataSourcePasswords),
            new ArrayList<>(termRoots.values()),
            new ArrayList<>(standardFields.values()),
            new ArrayList<>(versions.values()),
            new LinkedHashMap<>(versionObjects),
            new LinkedHashMap<>(versionLinks),
            new ArrayList<>(etlJobs.values()),
            new ArrayList<>(queryHistory.values()),
            new ArrayList<>(changeLogs),
            t1Schedule,
            new ArrayList<>(computeResources.values()),
            new LinkedHashMap<>(computeResourcePasswords),
            activeComputeResourceId
        );
    }

    private static <K, V> void putAll(Map<K, V> target, List<V> rows, Function<V, K> keyMapper) {
        if (rows == null) {
            return;
        }
        target.putAll(rows.stream().collect(Collectors.toMap(keyMapper, Function.identity(), (left, right) -> right, LinkedHashMap::new)));
    }

    private Map<Long, List<ObjectType>> normalizeVersionObjects(Map<Long, List<ObjectType>> source) {
        if (source == null) {
            return Map.of();
        }
        return objectMapper.convertValue(source, new TypeReference<LinkedHashMap<Long, List<ObjectType>>>() {
        });
    }

    private Map<Long, List<LinkType>> normalizeVersionLinks(Map<Long, List<LinkType>> source) {
        if (source == null) {
            return Map.of();
        }
        return objectMapper.convertValue(source, new TypeReference<LinkedHashMap<Long, List<LinkType>>>() {
        });
    }

    private List<DataSource> normalizeDataSources(List<DataSource> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(this::normalizeDataSource).toList();
    }

    private List<ComputeResource> normalizeComputeResources(List<ComputeResource> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(this::normalizeComputeResource).toList();
    }

    private DataSource normalizeDataSource(DataSource source) {
        String dbType = valueOr(source.dbType(), "DM");
        String host = valueOr(source.host(), "127.0.0.1");
        String databaseName = valueOr(source.databaseName(), source.code().toLowerCase(Locale.ROOT));
        String jdbcUrl = valueOr(source.jdbcUrl(), defaultJdbcUrl(dbType, host, source.port(), databaseName));
        String driverClassName = valueOr(source.driverClassName(), driverClassName(dbType));
        boolean passwordConfigured = source.passwordConfigured() || dataSourcePasswords.containsKey(source.id());
        return new DataSource(
            source.id(),
            source.code(),
            source.name(),
            source.domain(),
            dbType,
            jdbcUrl,
            driverClassName,
            host,
            source.port(),
            databaseName,
            source.username(),
            passwordConfigured,
            source.status(),
            source.readonly(),
            source.maxConcurrency(),
            source.healthStatus(),
            source.healthMessage(),
            source.lastHealthTime()
        );
    }

    private ComputeResource normalizeComputeResource(ComputeResource resource) {
        String dbType = valueOr(resource.dbType(), "MYSQL");
        String host = valueOr(resource.host(), "127.0.0.1");
        String metaDatabaseName = valueOr(resource.metaDatabaseName(), "ontology_meta");
        String instanceDatabaseName = valueOr(resource.instanceDatabaseName(), "ontology_instance");
        int port = resource.port() == 0 ? defaultPort(dbType) : resource.port();
        String jdbcUrl = valueOr(resource.jdbcUrl(), defaultJdbcUrl(dbType, host, port, metaDatabaseName));
        boolean passwordConfigured = resource.passwordConfigured() || computeResourcePasswords.containsKey(resource.id());
        return new ComputeResource(
            resource.id(),
            resource.code(),
            resource.name(),
            dbType,
            host,
            port,
            valueOr(resource.username(), "root"),
            passwordConfigured,
            metaDatabaseName,
            instanceDatabaseName,
            jdbcUrl,
            valueOr(resource.status(), "INACTIVE"),
            valueOr(resource.healthStatus(), "UNKNOWN"),
            valueOr(resource.healthMessage(), "待巡检"),
            resource.initialized(),
            resource.active(),
            resource.lastHealthTime()
        );
    }

    private String defaultJdbcUrl(String dbType, String host, int port, String databaseName) {
        String normalized = valueOr(dbType, "DM").toUpperCase(Locale.ROOT);
        if (normalized.contains("MYSQL") || normalized.contains("POLAR")) {
            return "jdbc:mysql://" + host + ":" + port + "/" + databaseName + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        }
        return "jdbc:dm://" + host + ":" + port + "/" + databaseName;
    }

    private String driverClassName(String dbType) {
        String normalized = valueOr(dbType, "DM").toUpperCase(Locale.ROOT);
        if (normalized.contains("MYSQL") || normalized.contains("POLAR")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        return "dm.jdbc.driver.DmDriver";
    }

    private int defaultPort(String dbType) {
        String normalized = valueOr(dbType, "MYSQL").toUpperCase(Locale.ROOT);
        return normalized.contains("DM") ? 5236 : 3306;
    }

    private static ScheduleConfig defaultSchedule() {
        return new ScheduleConfig("01:00", "ACTIVE", 4, List.of("对象抽取", "关系构建", "质量门禁", "快照发布"), Instant.now());
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
