package com.ontology.lite.service;

import com.ontology.lite.model.PlatformModels.*;
import com.ontology.lite.service.ComputeResourceSchemaInitializer.TableNames;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

final class OntologyMetaStore {
    private static final long TENANT_ID = 1L;

    void syncSnapshot(
        Connection connection,
        List<DataSource> dataSources,
        List<TermRoot> termRoots,
        List<StandardField> standardFields,
        List<ModelVersion> versions,
        Map<Long, List<ObjectType>> versionObjects,
        Map<Long, List<LinkType>> versionLinks,
        List<EtlJob> etlJobs,
        List<QueryResult> queryHistory,
        List<ChangeLog> changeLogs,
        List<ComputeResource> computeResources,
        Map<Long, String> computeResourcePasswords,
        ScheduleConfig scheduleConfig,
        TableNames tables
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            clear(statement, tables);
            insertTenant(statement, tables);
            insertDataSources(statement, dataSources, tables);
            insertComputeResources(statement, computeResources, computeResourcePasswords, tables);
            insertDictionary(statement, termRoots, standardFields, tables);
            insertModels(statement, versions, versionObjects, versionLinks, dataSources, tables);
            insertRuntime(statement, etlJobs, queryHistory, changeLogs, scheduleConfig, tables);
        }
    }

    private void clear(Statement statement, TableNames tables) throws SQLException {
        for (String table : List.of(
            "om_compute_resource_health", "om_compute_resource", "om_data_source_health", "om_data_source", "om_term_root", "om_standard_field", "om_root_field_map",
            "om_model_version", "om_object_type", "om_object_identifier", "om_property", "om_link_type",
            "om_mapping_rule", "om_schedule_config", "om_etl_watermark", "om_etl_run_log",
            "om_quality_result", "om_query_history", "om_change_log", "om_tenant"
        )) {
            statement.executeUpdate("DELETE FROM " + tables.meta(table));
        }
    }

    private void insertTenant(Statement statement, TableNames tables) throws SQLException {
        statement.executeUpdate("INSERT INTO " + tables.meta("om_tenant") + " (id, tenant_code, tenant_name, status) VALUES (1, 'default', '默认租户', 'ACTIVE')");
    }

    private void insertDataSources(Statement statement, List<DataSource> dataSources, TableNames tables) throws SQLException {
        for (DataSource source : dataSources) {
            statement.executeUpdate(String.format("""
                INSERT INTO %s (id, tenant_id, source_code, source_name, db_type, host, port, db_name, schema_name, username, secret_ref, max_concurrency, status)
                VALUES (%d, %d, '%s', '%s', '%s', '%s', %d, '%s', '%s', '%s', '%s', %d, '%s')
                """,
                tables.meta("om_data_source"),
                source.id(), TENANT_ID, sql(source.code()), sql(source.name()), sql(source.dbType()), sql(source.host()), source.port(),
                sql(source.databaseName()), sql(source.databaseName()), sql(source.username()), source.passwordConfigured() ? "STATE_PASSWORD" : "EMPTY",
                source.maxConcurrency(), "ACTIVE".equals(source.status()) ? "ACTIVE" : "INACTIVE"
            ));
            if (source.lastHealthTime() != null) {
                statement.executeUpdate(String.format("""
                    INSERT INTO %s (tenant_id, source_id, check_time, health_status, error_message, response_ms)
                    VALUES (%d, %d, CURRENT_TIMESTAMP, '%s', '%s', 0)
                    """, tables.meta("om_data_source_health"), TENANT_ID, source.id(), sql(source.healthStatus()), sql(source.healthMessage())));
            }
        }
    }

    private void insertComputeResources(Statement statement, List<ComputeResource> computeResources, Map<Long, String> computeResourcePasswords, TableNames tables) throws SQLException {
        for (ComputeResource resource : computeResources) {
            String password = computeResourcePasswords == null ? "" : computeResourcePasswords.getOrDefault(resource.id(), "");
            insertComputeStore(statement, resource, "META", resource.metaDatabaseName(), password, tables);
            insertComputeStore(statement, resource, "INSTANCE", resource.instanceDatabaseName(), password, tables);
            if (resource.lastHealthTime() != null) {
                insertComputeHealth(statement, resource, "META", resource.metaDatabaseName(), tables);
                insertComputeHealth(statement, resource, "INSTANCE", resource.instanceDatabaseName(), tables);
            }
        }
    }

    private void insertComputeStore(Statement statement, ComputeResource resource, String storeType, String databaseName, String password, TableNames tables) throws SQLException {
        statement.executeUpdate(String.format("""
            INSERT INTO %s (tenant_id, compute_resource_id, resource_code, resource_name, store_type, db_type, host, port, database_name, schema_name, username, secret_ref, password_value, jdbc_url, status, health_status, health_message, initialized, active, last_health_time)
            VALUES (%d, %d, '%s', '%s', '%s', '%s', '%s', %d, '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', %d, %d, %s)
            """,
            tables.meta("om_compute_resource"),
            TENANT_ID, resource.id(), sql(resource.code()), sql(resource.name()), storeType, sql(resource.dbType()), sql(resource.host()), resource.port(),
            sql(databaseName), sql(databaseName), sql(resource.username()), password == null || password.isBlank() ? "EMPTY" : "PLAIN_PASSWORD", sql(password),
            sql(resource.jdbcUrl()), sql(resource.status()), sql(resource.healthStatus()), sql(resource.healthMessage()),
            resource.initialized() ? 1 : 0, resource.active() ? 1 : 0, timestampSql(resource.lastHealthTime())
        ));
    }

    private void insertComputeHealth(Statement statement, ComputeResource resource, String storeType, String databaseName, TableNames tables) throws SQLException {
        statement.executeUpdate(String.format("""
            INSERT INTO %s (tenant_id, compute_resource_id, store_type, database_name, check_time, health_status, error_message, response_ms)
            VALUES (%d, %d, '%s', '%s', %s, '%s', '%s', 0)
            """, tables.meta("om_compute_resource_health"), TENANT_ID, resource.id(), storeType, sql(databaseName), timestampSql(resource.lastHealthTime()), sql(resource.healthStatus()), sql(resource.healthMessage())));
    }

    private void insertDictionary(Statement statement, List<TermRoot> termRoots, List<StandardField> standardFields, TableNames tables) throws SQLException {
        List<TermRoot> visibleRoots = termRoots.stream().filter(root -> !"REJECTED".equals(root.status())).toList();
        for (TermRoot root : visibleRoots) {
            statement.executeUpdate(String.format("""
                INSERT INTO %s (id, tenant_id, root_code, root_name, biz_definition, domain_name, source_type, confidence_level, evidence_json, review_note, status)
                VALUES (%d, %d, '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s')
                """, tables.meta("om_term_root"), root.id(), TENANT_ID, sql(root.code()), sql(root.name()), sql(root.definition()), sql(root.domain()), sql(root.sourceType()), sql(root.confidenceLevel()), sql(root.evidenceJson()), sql(root.reviewNote()), sql(root.status())));
            for (SimilarRoot similarRoot : root.similarRoots() == null ? List.<SimilarRoot>of() : root.similarRoots()) {
                visibleRoots.stream()
                    .filter(candidate -> candidate.code().equals(similarRoot.code()))
                    .findFirst()
                    .ifPresent(target -> {
                        try {
                            statement.executeUpdate(String.format("""
                                INSERT INTO %s (tenant_id, root_id, synonym_root_id, relation_type, weight_score, status)
                                VALUES (%d, %d, %d, '%s', %.2f, '%s')
                                """, tables.meta("om_root_synonym"), TENANT_ID, root.id(), target.id(), sql(similarRoot.relationType()), similarRoot.weight(), "ACTIVE".equals(root.status()) ? "ACTIVE" : "INACTIVE"));
                        } catch (SQLException ex) {
                            throw new IllegalStateException(ex);
                        }
                    });
            }
        }
        for (StandardField field : standardFields.stream().filter(field -> !"REJECTED".equals(field.status())).toList()) {
            statement.executeUpdate(String.format("""
                INSERT INTO %s (id, tenant_id, field_code, field_name, field_type, biz_definition, source_type, confidence_level, evidence_json, review_note, status)
                VALUES (%d, %d, '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s')
                """, tables.meta("om_standard_field"), field.id(), TENANT_ID, sql(field.code()), sql(field.name()), sql(field.dataType()), sql(field.description()), sql(field.sourceType()), sql(field.confidenceLevel()), sql(field.evidenceJson()), sql(field.reviewNote()), sql(field.status())));
            for (String rootCode : field.rootCodes()) {
                visibleRoots.stream()
                    .filter(root -> root.code().equals(rootCode))
                    .findFirst()
                    .ifPresent(root -> {
                        try {
                            statement.executeUpdate(String.format("""
                                INSERT INTO %s (tenant_id, root_id, standard_field_id, relation_type, weight_score, status)
                                VALUES (%d, %d, %d, 'SYNONYM', 1.00, '%s')
                                """, tables.meta("om_root_field_map"), TENANT_ID, root.id(), field.id(), "ACTIVE".equals(field.status()) ? "ACTIVE" : "INACTIVE"));
                        } catch (SQLException ex) {
                            throw new IllegalStateException(ex);
                        }
                    });
            }
        }
    }

    private void insertModels(Statement statement, List<ModelVersion> versions, Map<Long, List<ObjectType>> versionObjects, Map<Long, List<LinkType>> versionLinks, List<DataSource> dataSources, TableNames tables) throws SQLException {
        long sourceId = dataSources.isEmpty() ? 1 : dataSources.get(0).id();
        for (ModelVersion version : versions) {
            statement.executeUpdate(String.format("""
                INSERT INTO %s (id, tenant_id, version_no, status, is_latest, note)
                VALUES (%d, %d, '%s', '%s', %d, '轻量版同步写入')
                """, tables.meta("om_model_version"), version.id(), TENANT_ID, sql(version.versionNo()), sql(version.status()), version.latest() ? 1 : 0));
            for (ObjectType object : versionObjects.getOrDefault(version.id(), List.of())) {
                statement.executeUpdate(String.format("""
                    INSERT INTO %s (id, tenant_id, model_version_id, object_code, api_name, display_name, domain_name, status, confidence_score, source_ref_json)
                    VALUES (%d, %d, %d, '%s', '%s', '%s', '%s', '%s', %.2f, '%s')
                    """, tables.meta("om_object_type"), object.id(), TENANT_ID, version.id(), sql(object.code()), sql(object.apiName()), sql(object.displayName()), sql(object.domain()), sql(object.status()), object.confidence(), sql("{\"table\":\"" + object.sourceTable() + "\"}")));
                statement.executeUpdate(String.format("""
                    INSERT INTO %s (tenant_id, model_version_id, object_type_id, identifier_name, property_codes, is_primary, status)
                    VALUES (%d, %d, %d, 'business_key', '%s', 1, 'ACTIVE')
                    """, tables.meta("om_object_identifier"), TENANT_ID, version.id(), object.id(), object.properties().isEmpty() ? object.apiName() : object.properties().get(0).apiName()));
                for (Property property : object.properties()) {
                    statement.executeUpdate(String.format("""
                        INSERT INTO %s (id, tenant_id, model_version_id, object_type_id, property_code, api_name, display_name, data_type, source_ref_json, confidence_score, status)
                        VALUES (%d, %d, %d, %d, '%s', '%s', '%s', '%s', '%s', %.2f, 'ACTIVE')
                        """, tables.meta("om_property"), property.id(), TENANT_ID, version.id(), object.id(), sql(property.code()), sql(property.apiName()), sql(property.displayName()), sql(property.dataType()), sql("{\"column\":\"" + property.sourceColumn() + "\"}"), property.confidence()));
                    statement.executeUpdate(String.format("""
                        INSERT INTO %s (tenant_id, model_version_id, source_id, object_type_id, property_id, source_schema, source_table, source_column, confidence_score, status)
                        VALUES (%d, %d, %d, %d, %d, '%s', '%s', '%s', %.2f, 'ACTIVE')
                        """, tables.meta("om_mapping_rule"), TENANT_ID, version.id(), sourceId, object.id(), property.id(), sql(object.domain()), sql(object.sourceTable()), sql(property.sourceColumn()), property.confidence()));
                }
            }
            for (LinkType link : versionLinks.getOrDefault(version.id(), List.of())) {
                long sourceObjectId = findObjectId(versionObjects.getOrDefault(version.id(), List.of()), link.sourceObject());
                long targetObjectId = findObjectId(versionObjects.getOrDefault(version.id(), List.of()), link.targetObject());
                statement.executeUpdate(String.format("""
                    INSERT INTO %s (id, tenant_id, model_version_id, link_code, api_name, display_name, source_object_id, target_object_id, status, confidence_score)
                    VALUES (%d, %d, %d, '%s', '%s', '%s', %d, %d, '%s', %.2f)
                    """, tables.meta("om_link_type"), link.id(), TENANT_ID, version.id(), sql(link.code()), sql(link.apiName()), sql(link.displayName()), sourceObjectId, targetObjectId, sql(link.status()), link.confidence()));
            }
        }
    }

    private void insertRuntime(Statement statement, List<EtlJob> etlJobs, List<QueryResult> queryHistory, List<ChangeLog> changeLogs, ScheduleConfig scheduleConfig, TableNames tables) throws SQLException {
        statement.executeUpdate(String.format("""
            INSERT INTO %s (tenant_id, schedule_code, schedule_name, cron_expr, run_mode, status)
            VALUES (%d, 't1', 'T+1同步', '%s', 'AUTO', '%s')
            """, tables.meta("om_schedule_config"), TENANT_ID, sql(scheduleConfig.runAt()), sql(scheduleConfig.status())));
        for (EtlJob job : etlJobs) {
            statement.executeUpdate(String.format("""
                INSERT INTO %s (tenant_id, job_id, job_type, start_time, end_time, duration_ms, status, ext_json)
                VALUES (%d, '%s', 'ETL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, '%s', '%s')
                """, tables.meta("om_etl_run_log"), TENANT_ID, sql(job.jobId()), sql(job.status()), sql("{\"trigger\":\"" + job.triggerMode() + "\"}")));
            for (QualityResult quality : job.qualityResults()) {
                statement.executeUpdate(String.format("""
                    INSERT INTO %s (tenant_id, job_id, metric_code, metric_value, threshold_value, pass_flag, severity, detail_json)
                    VALUES (%d, '%s', 'dup_rate', %.6f, 0.000000, %d, 'INFO', '%s')
                    """, tables.meta("om_quality_result"), TENANT_ID, sql(job.jobId()), quality.duplicateRate(), "PASS".equals(quality.status()) ? 1 : 0, sql("{\"object\":\"" + quality.objectName() + "\"}")));
            }
            for (Watermark watermark : job.watermarks()) {
                statement.executeUpdate(String.format("""
                    INSERT INTO %s (tenant_id, source_id, source_schema, source_table, strategy_type, watermark_value, last_success_time)
                    VALUES (%d, 1, '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP)
                    """, tables.meta("om_etl_watermark"), TENANT_ID, sql(watermark.sourceCode()), sql(watermark.objectName()), sql(watermark.strategy()), sql(watermark.value())));
            }
        }
        for (QueryResult query : queryHistory) {
            statement.executeUpdate(String.format("""
                INSERT INTO %s (tenant_id, query_id, request_id, raw_question, generated_sql, executed_sql, execution_ms, result_rows, status)
                VALUES (%d, '%s', '%s', '%s', '%s', '%s', %d, %d, '%s')
                """, tables.meta("om_query_history"), TENANT_ID, sql(query.queryId()), "", sql(query.question()), sql(query.generatedSql()), sql(query.generatedSql()), (int) query.elapsedMs(), query.rowCount(), sql(query.status())));
        }
        for (ChangeLog log : changeLogs) {
            statement.executeUpdate(String.format("""
                INSERT INTO %s (id, tenant_id, change_type, entity_type, entity_code, operator_id)
                VALUES (%d, %d, '%s', '%s', '%s', '%s')
                """, tables.meta("om_change_log"), log.id(), TENANT_ID, sql(log.action()), sql(log.entityType()), sql(log.summary()), sql(log.operator())));
        }
    }

    private long findObjectId(List<ObjectType> objects, String apiName) {
        return objects.stream().filter(object -> object.apiName().equals(apiName)).map(ObjectType::id).findFirst().orElse(0L);
    }

    private String sql(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private String timestampSql(java.time.Instant value) {
        return value == null ? "NULL" : "'" + value.toString().replace("T", " ").replace("Z", "") + "'";
    }
}
