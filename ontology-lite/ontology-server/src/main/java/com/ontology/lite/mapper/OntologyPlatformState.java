package com.ontology.lite.mapper;

import com.ontology.lite.model.PlatformModels.*;

import java.util.List;
import java.util.Map;

public record OntologyPlatformState(
    long nextId,
    List<DataSource> dataSources,
    Map<Long, String> dataSourcePasswords,
    List<TermRoot> termRoots,
    List<StandardField> standardFields,
    List<ModelVersion> versions,
    Map<Long, List<ObjectType>> versionObjects,
    Map<Long, List<LinkType>> versionLinks,
    List<EtlJob> etlJobs,
    List<QueryResult> queryHistory,
    List<ChangeLog> changeLogs,
    ScheduleConfig t1Schedule,
    List<ComputeResource> computeResources,
    Map<Long, String> computeResourcePasswords,
    Long activeComputeResourceId
) {
}
