package com.ontology.lite.mapper;

import com.ontology.lite.model.PlatformModels.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryOntologyDataMapper implements OntologyDataMapper {
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
    private ScheduleConfig t1Schedule = new ScheduleConfig("01:00", "ACTIVE", 4, List.of("对象抽取", "关系构建", "质量门禁", "快照发布"), Instant.now());
    private Long activeComputeResourceId;

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
    }
}
