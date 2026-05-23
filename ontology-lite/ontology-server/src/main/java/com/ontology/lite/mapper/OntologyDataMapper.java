package com.ontology.lite.mapper;

import com.ontology.lite.model.PlatformModels.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public interface OntologyDataMapper {
    AtomicLong ids();

    Map<Long, DataSource> dataSources();

    Map<Long, String> dataSourcePasswords();

    Map<Long, ComputeResource> computeResources();

    Map<Long, String> computeResourcePasswords();

    Long activeComputeResourceId();

    void saveActiveComputeResourceId(Long id);

    Map<Long, TermRoot> termRoots();

    Map<Long, StandardField> standardFields();

    Map<Long, ModelVersion> versions();

    Map<Long, List<ObjectType>> versionObjects();

    Map<Long, List<LinkType>> versionLinks();

    Map<String, EtlJob> etlJobs();

    Map<String, QueryResult> queryHistory();

    List<ChangeLog> changeLogs();

    ScheduleConfig t1Schedule();

    void saveT1Schedule(ScheduleConfig scheduleConfig);

    default void persist() {
    }

    default boolean initialized() {
        return false;
    }
}
