package com.ontology.lite.controller;

import com.ontology.lite.common.ApiResponse;
import com.ontology.lite.model.PlatformModels.*;
import com.ontology.lite.service.OntologyPlatformService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class OntologyController {
    private final OntologyPlatformService service;

    public OntologyController(OntologyPlatformService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardSummary> dashboard() {
        return ApiResponse.ok(service.dashboard());
    }

    @GetMapping("/acceptance")
    public ApiResponse<Map<String, Object>> acceptance() {
        return ApiResponse.ok(service.acceptanceStatus());
    }

    @GetMapping("/internal/datasources")
    public ApiResponse<PageResult<DataSource>> dataSources() {
        List<DataSource> rows = service.listDataSources();
        return ApiResponse.ok(new PageResult<>(rows, rows.size()));
    }

    @PostMapping("/internal/datasources")
    public ApiResponse<DataSource> createDataSource(@Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.ok(service.createDataSource(request));
    }

    @PutMapping("/internal/datasources/{id}")
    public ApiResponse<DataSource> updateDataSource(@PathVariable long id, @Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.ok(service.updateDataSource(id, request));
    }

    @DeleteMapping("/internal/datasources/{id}")
    public ApiResponse<DataSource> deleteDataSource(@PathVariable long id) {
        return ApiResponse.ok(service.deleteDataSource(id));
    }

    @PostMapping("/internal/datasources/{id}/enable")
    public ApiResponse<DataSource> enableDataSource(@PathVariable long id) {
        return ApiResponse.ok(service.toggleDataSource(id, true));
    }

    @PostMapping("/internal/datasources/{id}/disable")
    public ApiResponse<DataSource> disableDataSource(@PathVariable long id) {
        return ApiResponse.ok(service.toggleDataSource(id, false));
    }

    @PostMapping("/internal/datasources/{id}/toggle")
    public ApiResponse<DataSource> toggleDataSource(@PathVariable long id, @RequestBody(required = false) Map<String, Object> request) {
        boolean active = request == null || !Boolean.FALSE.equals(request.get("active"));
        return ApiResponse.ok(service.toggleDataSource(id, active));
    }

    @PostMapping("/internal/datasources/{id}/test")
    public ApiResponse<ConnectionTestResult> testDataSource(@PathVariable long id) {
        return ApiResponse.ok(service.testDataSource(id));
    }

    @PostMapping("/internal/datasources/{id}/readonly-check")
    public ApiResponse<ConnectionTestResult> readonlyCheck(@PathVariable long id) {
        return ApiResponse.ok(service.testDataSource(id));
    }

    @GetMapping("/internal/datasources/{id}/health")
    public ApiResponse<ConnectionTestResult> dataSourceHealth(@PathVariable long id) {
        return ApiResponse.ok(service.dataSourceHealth(id));
    }

    @PostMapping("/internal/datasources/inspect")
    public ApiResponse<List<ConnectionTestResult>> inspectDataSources() {
        return ApiResponse.ok(service.inspectDataSources());
    }

    @GetMapping("/internal/compute-resources")
    public ApiResponse<PageResult<ComputeResource>> computeResources() {
        List<ComputeResource> rows = service.listComputeResources();
        return ApiResponse.ok(new PageResult<>(rows, rows.size()));
    }

    @PostMapping("/internal/compute-resources")
    public ApiResponse<ComputeResource> createComputeResource(@Valid @RequestBody ComputeResourceRequest request) {
        return ApiResponse.ok(service.createComputeResource(request));
    }

    @PutMapping("/internal/compute-resources/{id}")
    public ApiResponse<ComputeResource> updateComputeResource(@PathVariable long id, @Valid @RequestBody ComputeResourceRequest request) {
        return ApiResponse.ok(service.updateComputeResource(id, request));
    }

    @DeleteMapping("/internal/compute-resources/{id}")
    public ApiResponse<ComputeResource> deleteComputeResource(@PathVariable long id) {
        return ApiResponse.ok(service.deleteComputeResource(id));
    }

    @PostMapping("/internal/compute-resources/{id}/test")
    public ApiResponse<ConnectionTestResult> testComputeResource(@PathVariable long id) {
        return ApiResponse.ok(service.testComputeResource(id));
    }

    @PostMapping("/internal/compute-resources/{id}/initialize")
    public ApiResponse<ComputeResourceInitResult> initializeComputeResource(@PathVariable long id) {
        return ApiResponse.ok(service.initializeComputeResource(id));
    }

    @PostMapping("/internal/compute-resources/{id}/activate")
    public ApiResponse<ComputeResource> activateComputeResource(@PathVariable long id) {
        return ApiResponse.ok(service.activateComputeResource(id));
    }

    @GetMapping("/internal/compute-resources/active")
    public ApiResponse<ComputeResource> activeComputeResource() {
        return ApiResponse.ok(service.activeComputeResource());
    }

    @GetMapping("/internal/dictionary/term-roots")
    public ApiResponse<List<TermRoot>> termRoots() {
        return ApiResponse.ok(service.listTermRoots());
    }

    @GetMapping("/internal/dictionary/roots")
    public ApiResponse<PageResult<TermRoot>> roots() {
        List<TermRoot> rows = service.listTermRoots();
        return ApiResponse.ok(new PageResult<>(rows, rows.size()));
    }

    @PostMapping("/internal/dictionary/term-roots")
    public ApiResponse<TermRoot> saveTermRoot(@Valid @RequestBody TermRootRequest request) {
        return ApiResponse.ok(service.saveTermRoot(request));
    }

    @PostMapping("/internal/dictionary/ai-generate")
    public ApiResponse<DataStandardGenerateResult> generateDataStandards(@RequestBody(required = false) DataStandardGenerateRequest request) {
        return ApiResponse.ok(service.generateDataStandards(request));
    }

    @PostMapping("/internal/dictionary/roots")
    public ApiResponse<TermRoot> saveRoot(@Valid @RequestBody TermRootRequest request) {
        return ApiResponse.ok(service.saveTermRoot(request));
    }

    @PutMapping("/internal/dictionary/roots/{id}")
    public ApiResponse<TermRoot> updateRoot(@PathVariable long id, @Valid @RequestBody TermRootRequest request) {
        return ApiResponse.ok(service.updateTermRoot(id, request));
    }

    @PostMapping("/internal/dictionary/term-roots/{id}/approve")
    public ApiResponse<TermRoot> approveTermRoot(@PathVariable long id, @RequestBody(required = false) ReviewDecisionRequest request) {
        return ApiResponse.ok(service.approveTermRoot(id, request));
    }

    @PostMapping("/internal/dictionary/term-roots/{id}/reject")
    public ApiResponse<TermRoot> rejectTermRoot(@PathVariable long id, @RequestBody(required = false) ReviewDecisionRequest request) {
        return ApiResponse.ok(service.rejectTermRoot(id, request));
    }

    @GetMapping("/internal/dictionary/standard-fields")
    public ApiResponse<List<StandardField>> standardFields() {
        return ApiResponse.ok(service.listStandardFields());
    }

    @PostMapping("/internal/dictionary/standard-fields")
    public ApiResponse<StandardField> saveStandardField(@Valid @RequestBody StandardFieldRequest request) {
        return ApiResponse.ok(service.saveStandardField(request));
    }

    @PostMapping("/internal/dictionary/standard-fields/{id}/approve")
    public ApiResponse<StandardField> approveStandardField(@PathVariable long id, @RequestBody(required = false) ReviewDecisionRequest request) {
        return ApiResponse.ok(service.approveStandardField(id, request));
    }

    @PostMapping("/internal/dictionary/standard-fields/{id}/reject")
    public ApiResponse<StandardField> rejectStandardField(@PathVariable long id, @RequestBody(required = false) ReviewDecisionRequest request) {
        return ApiResponse.ok(service.rejectStandardField(id, request));
    }

    @PostMapping("/internal/dictionary/mappings")
    public ApiResponse<Map<String, Object>> saveMapping(@Valid @RequestBody StandardFieldRequest request) {
        return ApiResponse.ok(service.saveDictionaryMapping(request));
    }

    @PostMapping("/internal/dictionary/import")
    public ApiResponse<Map<String, Object>> importDictionary(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.ok(service.importDictionary(request == null ? "" : String.valueOf(request.getOrDefault("content", ""))));
    }

    @GetMapping("/internal/dictionary/import/{jobId}/errors")
    public ApiResponse<Map<String, Object>> dictionaryImportErrors(@PathVariable String jobId) {
        return ApiResponse.ok(service.dictionaryImportErrors(jobId));
    }

    @GetMapping("/internal/dictionary/export")
    public ApiResponse<Map<String, Object>> exportDictionary() {
        return ApiResponse.ok(service.exportDictionaryCsv());
    }

    @GetMapping("/internal/dictionary/audit-logs")
    public ApiResponse<List<ChangeLog>> dictionaryAuditLogs() {
        return ApiResponse.ok(service.changeLogs().stream().filter(item -> "DICTIONARY".equals(item.entityType())).toList());
    }

    @PostMapping("/internal/modeling/scan")
    public ApiResponse<ScanResult> scan(@RequestBody(required = false) ScanRequest request) {
        return ApiResponse.ok(service.scanMetadata(request == null ? new ScanRequest(null, null) : request));
    }

    @PostMapping("/internal/modeling/candidates/generate")
    public ApiResponse<CandidateResult> generateCandidates(@RequestBody(required = false) ScanRequest request) {
        return ApiResponse.ok(service.generateCandidates(request == null ? new ScanRequest(null, null) : request));
    }

    @GetMapping("/internal/modeling/candidates/{versionId}")
    public ApiResponse<CandidateResult> candidates(@PathVariable long versionId) {
        return ApiResponse.ok(service.candidates(versionId));
    }

    @PostMapping("/internal/modeling/candidates/{versionId}/revise")
    public ApiResponse<CandidateResult> reviseCandidate(@PathVariable long versionId, @RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.ok(service.reviseCandidate(versionId, request == null ? Map.of() : request));
    }

    @GetMapping("/internal/modeling/versions")
    public ApiResponse<List<ModelVersion>> versions() {
        return ApiResponse.ok(service.listVersions());
    }

    @GetMapping("/internal/modeling/versions/active")
    public ApiResponse<ModelVersion> activeVersion() {
        return ApiResponse.ok(service.activeVersion());
    }

    @GetMapping("/internal/modeling/versions/{versionId}/impact")
    public ApiResponse<PublishImpact> impact(@PathVariable long versionId) {
        return ApiResponse.ok(service.impact(versionId));
    }

    @PostMapping("/internal/modeling/publish")
    public ApiResponse<ModelVersion> publishLatest(@RequestBody PublishRequest request) {
        return ApiResponse.ok(service.publishLatestDraft(request));
    }

    @PostMapping("/internal/modeling/versions/{versionId}/publish")
    public ApiResponse<ModelVersion> publish(@PathVariable long versionId, @RequestBody PublishRequest request) {
        return ApiResponse.ok(service.publishVersion(versionId, request));
    }

    @PostMapping("/internal/modeling/rollback-latest")
    public ApiResponse<ModelVersion> rollback(@RequestParam(defaultValue = "1") int count) {
        return ApiResponse.ok(service.rollbackLatest(count));
    }

    @GetMapping("/internal/modeling/audit-logs")
    public ApiResponse<List<ChangeLog>> modelingAuditLogs() {
        return ApiResponse.ok(service.changeLogs().stream()
            .filter(item -> "MODELING".equals(item.entityType()) || "MODEL_VERSION".equals(item.entityType()))
            .toList());
    }

    @PostMapping("/internal/etl/jobs/t1/trigger")
    public ApiResponse<EtlJob> triggerEtl() {
        return ApiResponse.ok(service.triggerEtl("MANUAL"));
    }

    @GetMapping("/internal/etl/jobs")
    public ApiResponse<List<EtlJob>> etlJobs() {
        return ApiResponse.ok(service.listEtlJobs());
    }

    @GetMapping("/internal/etl/jobs/{jobId}")
    public ApiResponse<EtlJob> etlJob(@PathVariable String jobId) {
        return ApiResponse.ok(service.getEtlJob(jobId));
    }

    @PostMapping("/internal/etl/jobs/{jobId}/retry-failed-shards")
    public ApiResponse<EtlJob> retryFailedShards(@PathVariable String jobId) {
        return ApiResponse.ok(service.retryFailedShards(jobId));
    }

    @GetMapping("/internal/schedules/t1")
    public ApiResponse<ScheduleConfig> schedule() {
        return ApiResponse.ok(service.t1Schedule());
    }

    @PutMapping("/internal/schedules/t1")
    public ApiResponse<ScheduleConfig> updateSchedule(@RequestBody ScheduleRequest request) {
        return ApiResponse.ok(service.updateT1Schedule(request));
    }

    @GetMapping("/internal/quality/jobs/{jobId}")
    public ApiResponse<List<QualityResult>> qualityReport(@PathVariable String jobId) {
        return ApiResponse.ok(service.qualityReport(jobId));
    }

    @PostMapping("/internal/query/jobs")
    public ApiResponse<QueryResult> submitQuery(@Valid @RequestBody QueryRequest request) {
        return ApiResponse.ok(service.submitQuery(request));
    }

    @GetMapping("/internal/query/jobs/{queryId}")
    public ApiResponse<QueryResult> queryResult(@PathVariable String queryId) {
        return ApiResponse.ok(service.getQuery(queryId));
    }

    @PostMapping("/internal/query/jobs/{queryId}/rerun")
    public ApiResponse<QueryResult> rerun(@PathVariable String queryId, @RequestBody QueryRequest request) {
        return ApiResponse.ok(service.submitQuery(new QueryRequest(
            request.question() == null || request.question().isBlank() ? service.getQuery(queryId).question() : request.question(),
            request.sessionId(),
            request.requestId(),
            request.sqlOverride()
        )));
    }

    @PostMapping("/internal/query/jobs/{queryId}/chart")
    public ApiResponse<ChartResult> chart(@PathVariable String queryId, @RequestBody(required = false) Map<String, Object> request) {
        String type = request == null ? "bar" : String.valueOf(request.getOrDefault("chartType", "bar"));
        return ApiResponse.ok(service.queryChart(queryId, type));
    }

    @GetMapping("/internal/query/jobs/{queryId}/download")
    public ApiResponse<Map<String, Object>> downloadQuery(@PathVariable String queryId) {
        return ApiResponse.ok(service.exportQueryCsv(queryId));
    }

    @GetMapping("/internal/audit/change-logs")
    public ApiResponse<List<ChangeLog>> changeLogs() {
        return ApiResponse.ok(service.changeLogs());
    }

    @GetMapping("/open/ontology/meta")
    public ApiResponse<OpenApiMeta> openMeta() {
        return ApiResponse.ok(service.openApiMeta());
    }

    @GetMapping("/open/ontology/object-types")
    public ApiResponse<List<ObjectType>> openObjectTypes() {
        return ApiResponse.ok(service.openApiMeta().objectTypes());
    }

    @GetMapping("/open/ontology/link-types")
    public ApiResponse<List<LinkType>> openLinkTypes() {
        return ApiResponse.ok(service.openApiMeta().linkTypes());
    }

    @GetMapping("/open/ontology/properties")
    public ApiResponse<List<Property>> openProperties() {
        return ApiResponse.ok(service.openProperties());
    }

    @GetMapping("/open/ontology/dictionary")
    public ApiResponse<Map<String, Object>> openDictionary() {
        OpenApiMeta meta = service.openApiMeta();
        return ApiResponse.ok(Map.of("termRoots", meta.termRoots(), "standardFields", meta.standardFields()));
    }

    @GetMapping("/open/ontology/instances/{objectApiName}")
    public ApiResponse<List<Map<String, Object>>> openInstances(@PathVariable String objectApiName) {
        return ApiResponse.ok(service.objectInstances(objectApiName));
    }

    @GetMapping("/open/instances/{objectApiName}")
    public ApiResponse<List<Map<String, Object>>> openInstancesCompat(@PathVariable String objectApiName) {
        return ApiResponse.ok(service.objectInstances(objectApiName));
    }

    @GetMapping("/open/query/jobs/{queryId}")
    public ApiResponse<QueryResult> openQueryResult(@PathVariable String queryId) {
        return ApiResponse.ok(service.getQuery(queryId));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusinessException(RuntimeException ex) {
        return ApiResponse.fail(400, ex.getMessage());
    }
}
