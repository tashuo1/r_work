package com.ontology.lite.model;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PlatformModels {
    private PlatformModels() {
    }

    public record PageResult<T>(List<T> rows, int total) {
    }

    public record DataSource(
        long id,
        String code,
        String name,
        String domain,
        String dbType,
        String jdbcUrl,
        String driverClassName,
        String host,
        int port,
        String databaseName,
        String username,
        boolean passwordConfigured,
        String status,
        boolean readonly,
        int maxConcurrency,
        String healthStatus,
        String healthMessage,
        Instant lastHealthTime
    ) {
    }

    public record DataSourceRequest(
        @NotBlank String code,
        @NotBlank String name,
        String domain,
        String dbType,
        String host,
        Integer port,
        String databaseName,
        String username,
        String password,
        Integer maxConcurrency,
        String jdbcUrl,
        String driverClassName
    ) {
    }

    public record ConnectionTestResult(
        boolean success,
        boolean readonly,
        int latencyMs,
        String message,
        Instant checkedAt
    ) {
    }

    public record ComputeResource(
        long id,
        String code,
        String name,
        String dbType,
        String host,
        int port,
        String username,
        boolean passwordConfigured,
        String metaDatabaseName,
        String instanceDatabaseName,
        String jdbcUrl,
        String status,
        String healthStatus,
        String healthMessage,
        boolean initialized,
        boolean active,
        Instant lastHealthTime
    ) {
    }

    public record ComputeResourceRequest(
        @NotBlank String code,
        @NotBlank String name,
        String dbType,
        String host,
        Integer port,
        String username,
        String password,
        String jdbcUrl,
        String metaDatabaseName,
        String instanceDatabaseName
    ) {
    }

    public record ComputeResourceInitResult(
        boolean success,
        String metaDatabaseName,
        String instanceDatabaseName,
        int createdSchemas,
        int createdTables,
        String message,
        Instant checkedAt
    ) {
    }

    public record SimilarRoot(String code, String name, String relationType, double weight, String note) {
    }

    public record TermRoot(
        long id,
        String code,
        String name,
        String domain,
        String definition,
        String status,
        Instant updatedAt,
        String sourceType,
        String confidenceLevel,
        String evidenceJson,
        String reviewNote,
        List<SimilarRoot> similarRoots
    ) {
        public TermRoot(long id, String code, String name, String domain, String definition, String status, Instant updatedAt) {
            this(id, code, name, domain, definition, status, updatedAt, "MANUAL", "L1", "", "", List.of());
        }
    }

    public record TermRootRequest(@NotBlank String code, @NotBlank String name, String domain, String definition) {
    }

    public record StandardField(
        long id,
        String code,
        String name,
        String dataType,
        String domain,
        String description,
        List<String> rootCodes,
        String status,
        Instant updatedAt,
        String sourceType,
        String confidenceLevel,
        String evidenceJson,
        String reviewNote
    ) {
        public StandardField(long id, String code, String name, String dataType, String domain, String description, List<String> rootCodes, String status, Instant updatedAt) {
            this(id, code, name, dataType, domain, description, rootCodes, status, updatedAt, "MANUAL", "L1", "", "");
        }
    }

    public record StandardFieldRequest(@NotBlank String code, @NotBlank String name, String dataType, String domain, String description, List<String> rootCodes) {
    }

    public record DataStandardGenerateRequest(List<Long> dataSourceIds) {
    }

    public record DataStandardGenerateResult(
        String jobId,
        String skillName,
        String executionMode,
        String skillPath,
        String stage,
        int sourceCount,
        int createdRootCount,
        int createdFieldCount,
        int skippedRootCount,
        int skippedFieldCount,
        String status,
        String message,
        List<String> warnings,
        long durationMs,
        Instant generatedAt
    ) {
    }

    public record DataStandardSkillRequest(
        String skillName,
        List<DataSource> sources,
        List<TermRoot> existingRoots,
        List<StandardField> existingFields
    ) {
    }

    public record DataStandardSkillResult(
        String executionId,
        String skillName,
        String executionMode,
        String skillPath,
        String stage,
        long durationMs,
        List<String> warnings,
        List<RootCandidate> rootCandidates,
        List<FieldCandidate> fieldCandidates,
        String message,
        Instant generatedAt
    ) {
        public record RootCandidate(
            String code,
            String name,
            String domain,
            String definition,
            String status,
            String sourceType,
            String confidenceLevel,
            String evidenceJson,
            String reviewNote,
            List<SimilarRoot> similarRoots
        ) {
        }

        public record FieldCandidate(
            String code,
            String name,
            String dataType,
            String domain,
            String description,
            List<String> rootCodes,
            String status,
            String sourceType,
            String confidenceLevel,
            String evidenceJson,
            String reviewNote
        ) {
        }
    }

    public record ReviewDecisionRequest(String operator, String remark) {
    }

    public record ModelVersion(long id, String versionNo, String status, boolean latest, int objectCount, int propertyCount, int relationCount, double approvalRate, Instant createdAt, Instant activatedAt) {
    }

    public record ObjectType(long id, String code, String apiName, String displayName, String domain, String sourceTable, String status, double confidence, List<Property> properties) {
    }

    public record Property(long id, String code, String apiName, String displayName, String dataType, String sourceColumn, String standardFieldCode, double confidence) {
    }

    public record LinkType(long id, String code, String apiName, String displayName, String sourceObject, String targetObject, String status, double confidence) {
    }

    public record ScanRequest(Long dataSourceId, String schemaName) {
    }

    public record ScanResult(String scanId, long dataSourceId, int tableCount, int columnCount, List<Map<String, Object>> tables, Instant scannedAt) {
    }

    public record CandidateResult(long versionId, List<ObjectType> objects, List<LinkType> relations, List<String> suggestions) {
    }

    public record PublishImpact(long versionId, int affectedObjects, int affectedProperties, int affectedRelations, int affectedTasks, List<String> warnings) {
    }

    public record PublishRequest(boolean confirmed, String operator, String remark) {
    }

    public record ChangeLog(long id, String entityType, String action, String operator, String summary, Instant createdAt) {
    }

    public record EtlJob(String jobId, String status, String triggerMode, int objectTasks, int relationTasks, int successTasks, int failedTasks, List<QualityResult> qualityResults, List<Watermark> watermarks, Instant startedAt, Instant finishedAt) {
    }

    public record QualityResult(String objectName, int rowCount, double rowDelta, double duplicateRate, double nullRate, String status) {
    }

    public record Watermark(String sourceCode, String objectName, String strategy, String value, Instant updatedAt) {
    }

    public record ScheduleConfig(String runAt, String status, int maxThreadsPerSource, List<String> dagNodes, Instant updatedAt) {
    }

    public record ScheduleRequest(String runAt, String status) {
    }

    public record QueryRequest(@NotBlank String question, String sessionId, String requestId, String sqlOverride) {
    }

    public record QueryResult(String queryId, String status, String question, String generatedSql, String explanationLevel, String summary, List<Map<String, Object>> rows, int rowCount, long elapsedMs, List<String> safetyChecks) {
    }

    public record ChartResult(String queryId, String chartType, List<String> dimensions, List<String> measures, List<Map<String, Object>> series) {
    }

    public record OpenApiMeta(List<ObjectType> objectTypes, List<LinkType> linkTypes, List<TermRoot> termRoots, List<StandardField> standardFields) {
    }

    public record DashboardSummary(
        int dataSourceCount,
        int activeDataSourceCount,
        int objectCount,
        int relationCount,
        int standardFieldCount,
        String activeVersion,
        String lastEtlStatus,
        int auditCount,
        String computeResourceStatus,
        String metaStoreStatus,
        String instanceStoreStatus,
        String runtimeStoreType,
        String runtimeStoreSummary,
        boolean fallbackMode,
        List<String> environmentWarnings,
        int pendingActionCount
    ) {
    }
}
