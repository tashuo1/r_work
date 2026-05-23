package com.ontology.lite.service;

import com.ontology.lite.model.PlatformModels.EtlJob;
import com.ontology.lite.model.PlatformModels.LinkType;
import com.ontology.lite.model.PlatformModels.ModelVersion;
import com.ontology.lite.model.PlatformModels.ObjectType;
import com.ontology.lite.service.ComputeResourceSchemaInitializer.TableNames;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class OntologyInstanceStore {
    private static final long TENANT_ID = 1L;

    void syncSnapshot(Connection connection, ModelVersion activeVersion, Map<Long, List<ObjectType>> versionObjects, Map<Long, List<LinkType>> versionLinks, List<EtlJob> etlJobs, TableNames tables) throws SQLException {
        if (activeVersion == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + tables.instance("om_action_event"));
            statement.executeUpdate("DELETE FROM " + tables.instance("om_relation_instance"));
            statement.executeUpdate("DELETE FROM " + tables.instance("om_object_instance"));
            String batchId = etlJobs.isEmpty() ? "bootstrap" : etlJobs.get(etlJobs.size() - 1).jobId();
            insertObjects(statement, activeVersion, versionObjects.getOrDefault(activeVersion.id(), List.of()), batchId, tables);
            insertRelations(statement, activeVersion, versionObjects.getOrDefault(activeVersion.id(), List.of()), versionLinks.getOrDefault(activeVersion.id(), List.of()), batchId, tables);
            insertActionEvent(statement, activeVersion, versionObjects.getOrDefault(activeVersion.id(), List.of()), batchId, tables);
        }
    }

    private void insertObjects(Statement statement, ModelVersion version, List<ObjectType> objects, String batchId, TableNames tables) throws SQLException {
        for (ObjectType object : objects) {
            for (int index = 1; index <= 2; index++) {
                String instanceId = object.apiName() + "_" + index;
                statement.executeUpdate(String.format("""
                    INSERT INTO %s (tenant_id, object_type_id, object_api_name, domain_name, instance_id, display_label, properties_json, batch_id, model_version_id, biz_date)
                    VALUES (%d, %d, '%s', '%s', '%s', '%s', '%s', '%s', %d, DATE '%s')
                    """, tables.instance("om_object_instance"), TENANT_ID, object.id(), sql(object.apiName()), sql(object.domain()), sql(instanceId), sql(object.displayName() + index), sql(propertiesJson(object, index)), sql(batchId), version.id(), LocalDate.now()));
            }
        }
    }

    private void insertRelations(Statement statement, ModelVersion version, List<ObjectType> objects, List<LinkType> links, String batchId, TableNames tables) throws SQLException {
        for (LinkType link : links) {
            ObjectType source = findObject(objects, link.sourceObject());
            ObjectType target = findObject(objects, link.targetObject());
            if (source == null || target == null) {
                continue;
            }
            statement.executeUpdate(String.format("""
                INSERT INTO %s (tenant_id, link_type_id, link_api_name, source_object_type_id, source_instance_id, target_object_type_id, target_instance_id, properties_json, start_date, batch_id, model_version_id)
                VALUES (%d, %d, '%s', %d, '%s', %d, '%s', '{}', DATE '%s', '%s', %d)
                """, tables.instance("om_relation_instance"), TENANT_ID, link.id(), sql(link.apiName()), source.id(), sql(source.apiName() + "_1"), target.id(), sql(target.apiName() + "_1"), LocalDate.now(), sql(batchId), version.id()));
        }
    }

    private void insertActionEvent(Statement statement, ModelVersion version, List<ObjectType> objects, String batchId, TableNames tables) throws SQLException {
        if (objects.isEmpty()) {
            return;
        }
        ObjectType object = objects.get(0);
        statement.executeUpdate(String.format("""
            INSERT INTO %s (tenant_id, event_id, object_type_id, object_instance_id, action_type_id, event_type, event_time, before_json, after_json, changed_fields_json, batch_id)
            VALUES (%d, '%s', %d, '%s', 0, 'STATUS_CHANGE', CURRENT_TIMESTAMP, '{}', '{}', '[]', '%s')
            """, tables.instance("om_action_event"), TENANT_ID, sql("evt-" + version.id() + "-" + batchId), object.id(), sql(object.apiName() + "_1"), sql(batchId)));
    }

    private String propertiesJson(ObjectType object, int index) {
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < object.properties().size(); i++) {
            var property = object.properties().get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(property.apiName()).append("\":\"")
                .append(property.displayName()).append(index).append('"');
        }
        builder.append('}');
        return builder.toString();
    }

    private ObjectType findObject(List<ObjectType> objects, String apiName) {
        return objects.stream().filter(object -> Objects.equals(object.apiName(), apiName)).findFirst().orElse(null);
    }

    private String sql(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
