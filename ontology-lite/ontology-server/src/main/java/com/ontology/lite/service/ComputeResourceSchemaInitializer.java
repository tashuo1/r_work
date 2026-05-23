package com.ontology.lite.service;

import com.ontology.lite.model.PlatformModels.ComputeResource;
import com.ontology.lite.model.PlatformModels.ComputeResourceInitResult;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ComputeResourceSchemaInitializer {
    private static final Map<String, String> TABLE_COMMENTS = tableComments();
    private static final Map<String, String> COLUMN_COMMENTS = columnComments();

    private static final List<TableSpec> META_TABLES = List.of(
        new TableSpec("om_tenant", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_code VARCHAR(64) NOT NULL,
            tenant_name VARCHAR(128) NOT NULL,
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_data_source", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            source_code VARCHAR(64) NOT NULL,
            source_name VARCHAR(128) NOT NULL,
            db_type VARCHAR(32) NOT NULL,
            db_version VARCHAR(128),
            charset_name VARCHAR(64),
            host VARCHAR(128) NOT NULL,
            port INT NOT NULL,
            db_name VARCHAR(128) NOT NULL,
            schema_name VARCHAR(128) NOT NULL,
            username VARCHAR(128) NOT NULL,
            secret_ref VARCHAR(256) NOT NULL,
            max_concurrency INT NOT NULL DEFAULT 10,
            health_check_cron VARCHAR(64) NOT NULL DEFAULT '0 */30 * * * ?',
            allowed_schemas_json LONGTEXT,
            last_health_time TIMESTAMP,
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_data_source_health", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            source_id BIGINT NOT NULL,
            check_time TIMESTAMP NOT NULL,
            health_status VARCHAR(32) NOT NULL,
            error_code VARCHAR(64),
            error_message VARCHAR(512),
            response_ms INT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_compute_resource", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            compute_resource_id BIGINT NOT NULL,
            resource_code VARCHAR(64) NOT NULL,
            resource_name VARCHAR(128) NOT NULL,
            store_type VARCHAR(16) NOT NULL,
            db_type VARCHAR(32) NOT NULL,
            host VARCHAR(128) NOT NULL,
            port INT NOT NULL,
            database_name VARCHAR(128) NOT NULL,
            schema_name VARCHAR(128) NOT NULL,
            username VARCHAR(128) NOT NULL,
            secret_ref VARCHAR(256) NOT NULL,
            password_value VARCHAR(512) NOT NULL,
            jdbc_url VARCHAR(1024) NOT NULL,
            status VARCHAR(16) NOT NULL,
            health_status VARCHAR(32) NOT NULL,
            health_message VARCHAR(512),
            initialized TINYINT NOT NULL DEFAULT 0,
            active TINYINT NOT NULL DEFAULT 0,
            last_health_time TIMESTAMP,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_compute_resource_health", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            compute_resource_id BIGINT NOT NULL,
            store_type VARCHAR(16) NOT NULL,
            database_name VARCHAR(128) NOT NULL,
            check_time TIMESTAMP NOT NULL,
            health_status VARCHAR(32) NOT NULL,
            error_message VARCHAR(512),
            response_ms INT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_model_version", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            version_no VARCHAR(64) NOT NULL,
            version_label VARCHAR(128),
            status VARCHAR(16) NOT NULL,
            is_latest TINYINT NOT NULL DEFAULT 1,
            effective_at TIMESTAMP,
            effective_by VARCHAR(64),
            rollback_from_id BIGINT,
            impact_summary_json LONGTEXT,
            note VARCHAR(512),
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_object_type", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            model_version_id BIGINT NOT NULL,
            object_code VARCHAR(64) NOT NULL,
            api_name VARCHAR(128) NOT NULL,
            display_name VARCHAR(128) NOT NULL,
            plural_display_name VARCHAR(128),
            description VARCHAR(512),
            domain_name VARCHAR(64),
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            title_property_code VARCHAR(128),
            primary_key_policy VARCHAR(32) NOT NULL DEFAULT 'BUSINESS_KEY',
            confidence_score DECIMAL(4,2),
            source_ref_json LONGTEXT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_property", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            model_version_id BIGINT NOT NULL,
            object_type_id BIGINT NOT NULL,
            property_code VARCHAR(128) NOT NULL,
            api_name VARCHAR(128) NOT NULL,
            display_name VARCHAR(128) NOT NULL,
            description VARCHAR(512),
            data_type VARCHAR(64) NOT NULL,
            is_required TINYINT NOT NULL DEFAULT 0,
            is_unique TINYINT NOT NULL DEFAULT 0,
            is_searchable TINYINT NOT NULL DEFAULT 1,
            is_title TINYINT NOT NULL DEFAULT 0,
            enum_values_json LONGTEXT,
            default_value VARCHAR(256),
            format_pattern VARCHAR(128),
            source_ref_json LONGTEXT,
            confidence_score DECIMAL(4,2),
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_object_identifier", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            model_version_id BIGINT NOT NULL,
            object_type_id BIGINT NOT NULL,
            identifier_name VARCHAR(128) NOT NULL,
            property_codes VARCHAR(1024) NOT NULL,
            is_primary TINYINT NOT NULL DEFAULT 0,
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_link_type", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            model_version_id BIGINT NOT NULL,
            link_code VARCHAR(64) NOT NULL,
            api_name VARCHAR(128) NOT NULL,
            display_name VARCHAR(128) NOT NULL,
            source_object_id BIGINT NOT NULL,
            target_object_id BIGINT NOT NULL,
            cardinality VARCHAR(16) NOT NULL DEFAULT 'N:N',
            link_category VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
            is_directional TINYINT NOT NULL DEFAULT 1,
            reverse_api_name VARCHAR(128),
            reverse_display_name VARCHAR(128),
            description VARCHAR(512),
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            confidence_score DECIMAL(4,2),
            source_ref_json LONGTEXT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_mapping_rule", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            model_version_id BIGINT NOT NULL,
            source_id BIGINT NOT NULL,
            object_type_id BIGINT NOT NULL,
            property_id BIGINT,
            source_schema VARCHAR(128) NOT NULL,
            source_table VARCHAR(128) NOT NULL,
            source_column VARCHAR(128),
            transform_expr VARCHAR(1024),
            increment_strategy VARCHAR(8) NOT NULL DEFAULT 'A',
            biz_key_flag TINYINT NOT NULL DEFAULT 0,
            mapping_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
            last_modeled_at TIMESTAMP,
            confidence_score DECIMAL(4,2),
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_term_root", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            root_code VARCHAR(128) NOT NULL,
            root_name VARCHAR(128) NOT NULL,
            biz_definition VARCHAR(512),
            domain_name VARCHAR(64),
            source_type VARCHAR(32),
            confidence_level VARCHAR(32),
            evidence_json LONGTEXT,
            review_note VARCHAR(512),
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_standard_field", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            field_code VARCHAR(128) NOT NULL,
            field_name VARCHAR(128) NOT NULL,
            field_type VARCHAR(64) NOT NULL,
            biz_definition VARCHAR(512),
            combo_key VARCHAR(256),
            source_type VARCHAR(32),
            confidence_level VARCHAR(32),
            evidence_json LONGTEXT,
            review_note VARCHAR(512),
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_root_field_map", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            root_id BIGINT NOT NULL,
            standard_field_id BIGINT NOT NULL,
            relation_type VARCHAR(32) NOT NULL DEFAULT 'SYNONYM',
            weight_score DECIMAL(4,2) DEFAULT 1.00,
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_root_synonym", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            root_id BIGINT NOT NULL,
            synonym_root_id BIGINT NOT NULL,
            relation_type VARCHAR(16) NOT NULL DEFAULT 'SYNONYM',
            weight_score DECIMAL(4,2) DEFAULT 1.00,
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_schedule_config", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            schedule_code VARCHAR(64) NOT NULL,
            schedule_name VARCHAR(128) NOT NULL,
            cron_expr VARCHAR(64) NOT NULL,
            schedule_scope VARCHAR(32) NOT NULL DEFAULT 'ALL',
            scope_ref_id BIGINT,
            timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
            run_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_etl_watermark", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            source_id BIGINT NOT NULL,
            source_schema VARCHAR(128) NOT NULL,
            source_table VARCHAR(128) NOT NULL,
            object_type_id BIGINT,
            strategy_type VARCHAR(8) NOT NULL,
            watermark_value VARCHAR(256),
            biz_date DATE,
            last_success_time TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_etl_run_log", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            job_id VARCHAR(64) NOT NULL,
            job_type VARCHAR(32) NOT NULL,
            source_id BIGINT,
            model_version_id BIGINT,
            biz_date DATE,
            start_time TIMESTAMP NOT NULL,
            end_time TIMESTAMP,
            duration_ms BIGINT,
            status VARCHAR(16) NOT NULL,
            retry_count INT NOT NULL DEFAULT 0,
            error_code VARCHAR(64),
            error_message VARCHAR(1024),
            ext_json LONGTEXT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_quality_result", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            job_id VARCHAR(64) NOT NULL,
            object_type_id BIGINT,
            metric_code VARCHAR(64) NOT NULL,
            metric_value DECIMAL(18,6) NOT NULL,
            threshold_value DECIMAL(18,6),
            pass_flag TINYINT NOT NULL DEFAULT 1,
            severity VARCHAR(16) DEFAULT 'INFO',
            detail_json LONGTEXT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_query_history", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            query_id VARCHAR(64) NOT NULL,
            request_id VARCHAR(128),
            raw_question VARCHAR(2048) NOT NULL,
            model_version_id BIGINT,
            generated_sql LONGTEXT,
            edited_sql LONGTEXT,
            executed_sql LONGTEXT,
            execution_ms INT,
            result_rows INT,
            status VARCHAR(16) NOT NULL,
            error_code VARCHAR(64),
            user_feedback VARCHAR(16),
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_change_log", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            model_version_id BIGINT,
            change_type VARCHAR(32) NOT NULL,
            entity_type VARCHAR(32) NOT NULL,
            entity_id BIGINT,
            entity_code VARCHAR(128),
            before_json LONGTEXT,
            after_json LONGTEXT,
            reason_code VARCHAR(64),
            operator_id VARCHAR(64) NOT NULL,
            operator_name VARCHAR(128),
            op_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            request_id VARCHAR(128),
            trace_id VARCHAR(128)
            """)
    );

    private static final List<TableSpec> INSTANCE_TABLES = List.of(
        new TableSpec("om_object_instance", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            object_type_id BIGINT NOT NULL,
            object_api_name VARCHAR(128) NOT NULL,
            domain_name VARCHAR(64),
            instance_id VARCHAR(128) NOT NULL,
            display_label VARCHAR(256),
            properties_json LONGTEXT NOT NULL,
            batch_id VARCHAR(64) NOT NULL,
            model_version_id BIGINT NOT NULL,
            biz_date DATE,
            is_deleted TINYINT NOT NULL DEFAULT 0,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_relation_instance", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            link_type_id BIGINT NOT NULL,
            link_api_name VARCHAR(128) NOT NULL,
            source_object_type_id BIGINT NOT NULL,
            source_instance_id VARCHAR(128) NOT NULL,
            target_object_type_id BIGINT NOT NULL,
            target_instance_id VARCHAR(128) NOT NULL,
            properties_json LONGTEXT,
            start_date DATE,
            end_date DATE,
            batch_id VARCHAR(64) NOT NULL,
            model_version_id BIGINT NOT NULL,
            is_deleted TINYINT NOT NULL DEFAULT 0,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """),
        new TableSpec("om_action_event", """
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            tenant_id BIGINT NOT NULL,
            event_id VARCHAR(64) NOT NULL,
            object_type_id BIGINT NOT NULL,
            object_instance_id VARCHAR(128) NOT NULL,
            action_type_id BIGINT NOT NULL,
            event_type VARCHAR(32) NOT NULL,
            event_time TIMESTAMP NOT NULL,
            before_json LONGTEXT,
            after_json LONGTEXT,
            changed_fields_json LONGTEXT,
            batch_id VARCHAR(64),
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            """)
    );

    public SchemaPlan plan(ComputeResource resource) {
        boolean dm = isDm(resource.dbType());
        String metaName = identifier(resource.metaDatabaseName());
        String instanceName = identifier(resource.instanceDatabaseName());
        List<String> schemas = dm
            ? List.of("CREATE SCHEMA IF NOT EXISTS " + metaName, "CREATE SCHEMA IF NOT EXISTS " + instanceName)
            : List.of("CREATE DATABASE IF NOT EXISTS " + quote(metaName, false), "CREATE DATABASE IF NOT EXISTS " + quote(instanceName, false));
        List<String> tables = new ArrayList<>();
        List<String> migrations = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        META_TABLES.forEach(table -> tables.add(createTableSql(metaName, table, dm)));
        INSTANCE_TABLES.forEach(table -> tables.add(createTableSql(instanceName, table, dm)));
        migrations.addAll(migrationSql(metaName, dm));
        META_TABLES.forEach(table -> comments.addAll(commentSql(metaName, table, dm)));
        INSTANCE_TABLES.forEach(table -> comments.addAll(commentSql(instanceName, table, dm)));
        return new SchemaPlan(schemas, tables, migrations, comments);
    }

    public ComputeResourceInitResult initialize(Connection connection, ComputeResource resource) {
        SchemaPlan schemaPlan = plan(resource);
        boolean h2 = isH2(connection);
        int createdSchemas = 0;
        int createdTables = 0;
        int skippedComments = 0;
        try (Statement statement = connection.createStatement()) {
            if (!h2) {
                for (String schemaSql : schemaPlan.schemaStatements()) {
                    statement.execute(schemaSql);
                    createdSchemas += 1;
                }
            }
            for (String tableSql : h2 ? h2TableStatements() : schemaPlan.tableStatements()) {
                statement.execute(tableSql);
                createdTables += 1;
            }
            if (!h2) {
                for (String migrationSql : schemaPlan.migrationStatements()) {
                    try {
                        statement.execute(migrationSql);
                    } catch (SQLException ex) {
                        if (!isIgnorableMigrationFailure(ex)) {
                            throw ex;
                        }
                    }
                }
            }
            if (!h2) {
                for (String commentSql : schemaPlan.commentStatements()) {
                    try {
                        statement.execute(commentSql);
                    } catch (SQLException ex) {
                        if (!isIgnorableCommentFailure(ex)) {
                            throw ex;
                        }
                        skippedComments += 1;
                    }
                }
            }
            String message = skippedComments == 0
                ? "计算资源初始化完成"
                : "计算资源初始化完成，缺失字段注释已跳过 " + skippedComments + " 条";
            return new ComputeResourceInitResult(true, resource.metaDatabaseName(), resource.instanceDatabaseName(), h2 ? 2 : createdSchemas, createdTables, message, Instant.now());
        } catch (SQLException ex) {
            return new ComputeResourceInitResult(false, resource.metaDatabaseName(), resource.instanceDatabaseName(), createdSchemas, createdTables, "计算资源初始化失败：" + sanitize(ex.getMessage()), Instant.now());
        }
    }

    private boolean isIgnorableMigrationFailure(SQLException ex) {
        String message = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        return ex.getErrorCode() == 1060 || message.contains("duplicate column") || message.contains("already exists");
    }

    private boolean isIgnorableCommentFailure(SQLException ex) {
        String message = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        return ex.getErrorCode() == 1054 || message.contains("unknown column");
    }

    public TableNames tableNames(ComputeResource resource) {
        return tableNames(resource, isH2(resource.jdbcUrl()));
    }

    public TableNames tableNames(Connection connection, ComputeResource resource) {
        return tableNames(resource, isH2(connection) || isH2(resource.jdbcUrl()));
    }

    private TableNames tableNames(ComputeResource resource, boolean h2) {
        return new TableNames(
            h2,
            isDm(resource.dbType()),
            identifier(resource.metaDatabaseName()),
            identifier(resource.instanceDatabaseName())
        );
    }

    private List<String> h2TableStatements() {
        List<String> statements = new ArrayList<>();
        META_TABLES.forEach(table -> statements.add(createH2TableSql("meta_" + table.name(), table)));
        INSTANCE_TABLES.forEach(table -> statements.add(createH2TableSql("instance_" + table.name(), table)));
        return statements;
    }

    private String createTableSql(String schemaName, TableSpec table, boolean dm) {
        String fullName = dm
            ? schemaName + "." + table.name()
            : quote(schemaName, false) + "." + quote(table.name(), false);
        String sql = "CREATE TABLE IF NOT EXISTS " + fullName + " (" + adaptColumns(table.columns(), dm) + ")";
        return dm ? sql : sql + " COMMENT='" + sqlComment(tableComment(table.name())) + "'";
    }

    private String createH2TableSql(String tableName, TableSpec table) {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " (" + table.columns() + ")";
    }

    private String adaptColumns(String columns, boolean dm) {
        String adapted = columns;
        if (dm) {
            adapted = adapted
                .replace("AUTO_INCREMENT", "IDENTITY")
                .replace("LONGTEXT", "CLOB")
                .replace("TINYINT", "SMALLINT")
                .replace("TIMESTAMP", "DATETIME");
        }
        return dm ? adapted : addInlineColumnComments(adapted);
    }

    private String addInlineColumnComments(String columns) {
        List<String> lines = new ArrayList<>();
        for (String line : columns.split("\\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.isBlank()) {
                lines.add(line);
                continue;
            }
            String suffix = trimmed.endsWith(",") ? "," : "";
            String withoutComma = suffix.isEmpty() ? line : line.substring(0, line.lastIndexOf(','));
            String columnName = trimmed.replaceFirst("\\s.*", "").replace(",", "");
            lines.add(withoutComma + " COMMENT '" + sqlComment(columnComment(columnName)) + "'" + suffix);
        }
        return String.join("\n", lines);
    }

    private List<String> commentSql(String schemaName, TableSpec table, boolean dm) {
        String tableName = dm
            ? schemaName + "." + table.name()
            : quote(schemaName, false) + "." + quote(table.name(), false);
        List<String> comments = new ArrayList<>();
        if (!dm) {
            comments.add("ALTER TABLE " + tableName + " COMMENT = '" + sqlComment(tableComment(table.name())) + "'");
            for (String columnDefinition : table.columnDefinitions()) {
                String columnName = columnDefinition.replaceFirst("\\s.*", "");
                comments.add("ALTER TABLE " + tableName + " MODIFY COLUMN " + mysqlColumnDefinitionForComment(columnDefinition, columnName));
            }
            return comments;
        }
        comments.add("COMMENT ON TABLE " + tableName + " IS '" + sqlComment(tableComment(table.name())) + "'");
        for (String columnName : table.columnNames()) {
            comments.add("COMMENT ON COLUMN " + tableName + "." + columnName + " IS '" + sqlComment(columnComment(columnName)) + "'");
        }
        return comments;
    }

    private List<String> migrationSql(String schemaName, boolean dm) {
        if (dm) {
            return List.of(
                "ALTER TABLE " + schemaName + ".om_compute_resource ADD tenant_id BIGINT DEFAULT 1 NOT NULL",
                "ALTER TABLE " + schemaName + ".om_compute_resource_health ADD tenant_id BIGINT DEFAULT 1 NOT NULL"
            );
        }
        String computeResource = quote(schemaName, false) + "." + quote("om_compute_resource", false);
        String computeResourceHealth = quote(schemaName, false) + "." + quote("om_compute_resource_health", false);
        return List.of(
            "ALTER TABLE " + computeResource + " ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '" + sqlComment(columnComment("tenant_id")) + "' AFTER id",
            "ALTER TABLE " + computeResourceHealth + " ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '" + sqlComment(columnComment("tenant_id")) + "' AFTER id"
        );
    }

    private String mysqlColumnDefinitionForComment(String columnDefinition, String columnName) {
        String normalized = columnDefinition.replaceFirst("(?i)\\s+PRIMARY\\s+KEY\\b", "");
        if (normalized.toUpperCase(Locale.ROOT).contains("AUTO_INCREMENT")
            && !normalized.toUpperCase(Locale.ROOT).contains("NOT NULL")) {
            normalized = normalized.replaceFirst("(?i)\\s+AUTO_INCREMENT\\b", " NOT NULL AUTO_INCREMENT");
        }
        return normalized + " COMMENT '" + sqlComment(columnComment(columnName)) + "'";
    }

    private boolean isH2(Connection connection) {
        try {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2");
        } catch (SQLException ex) {
            return false;
        }
    }

    private boolean isH2(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:");
    }

    private String quote(String identifier, boolean dm) {
        return dm ? identifier(identifier) : "`" + identifier(identifier) + "`";
    }

    private String identifier(String value) {
        String normalized = value == null || value.isBlank() ? "ontology_meta" : value.trim();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("数据库/Schema 名称只能包含字母、数字、下划线，且必须以字母或下划线开头");
        }
        return normalized;
    }

    private boolean isDm(String dbType) {
        return String.valueOf(dbType).toUpperCase(Locale.ROOT).contains("DM");
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "数据库未返回错误信息";
        }
        return message.replaceAll("(?i)(password|pwd)\\s*=\\s*[^,;\\s]+", "$1=***");
    }

    public record SchemaPlan(List<String> schemaStatements, List<String> tableStatements, List<String> migrationStatements, List<String> commentStatements) {
    }

    public static final class TableNames {
        private final boolean h2;
        private final boolean dm;
        private final String metaDatabaseName;
        private final String instanceDatabaseName;

        private TableNames(boolean h2, boolean dm, String metaDatabaseName, String instanceDatabaseName) {
            this.h2 = h2;
            this.dm = dm;
            this.metaDatabaseName = metaDatabaseName;
            this.instanceDatabaseName = instanceDatabaseName;
        }

        public String meta(String tableName) {
            return qualify(metaDatabaseName, "meta_", tableName);
        }

        public String instance(String tableName) {
            return qualify(instanceDatabaseName, "instance_", tableName);
        }

        private String qualify(String databaseName, String h2Prefix, String tableName) {
            String normalizedTableName = identifier(tableName);
            if (h2) {
                return h2Prefix + normalizedTableName;
            }
            if (dm) {
                return databaseName + "." + normalizedTableName;
            }
            return "`" + databaseName + "`.`" + normalizedTableName + "`";
        }

        private String identifier(String value) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("表名只能包含字母、数字、下划线，且必须以字母或下划线开头");
            }
            return normalized;
        }
    }

    private record TableSpec(String name, String columns) {
        private List<String> columnDefinitions() {
            List<String> definitions = new ArrayList<>();
            for (String line : columns.split("\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    definitions.add(trimmed.replaceFirst(",$", ""));
                }
            }
            return definitions;
        }

        private List<String> columnNames() {
            List<String> names = new ArrayList<>();
            for (String definition : columnDefinitions()) {
                names.add(definition.replaceFirst("\\s.*", ""));
            }
            return names;
        }
    }

    private static String tableComment(String tableName) {
        return TABLE_COMMENTS.getOrDefault(tableName, tableName + "表");
    }

    private static String columnComment(String columnName) {
        return COLUMN_COMMENTS.getOrDefault(columnName, columnName);
    }

    private static String sqlComment(String value) {
        return value.replace("'", "''");
    }

    private static Map<String, String> tableComments() {
        Map<String, String> comments = new LinkedHashMap<>();
        comments.put("om_tenant", "租户表");
        comments.put("om_data_source", "业务数据源配置表");
        comments.put("om_data_source_health", "业务数据源健康巡检记录表");
        comments.put("om_compute_resource", "计算资源数据库连接信息表");
        comments.put("om_compute_resource_health", "计算资源数据库健康记录表");
        comments.put("om_model_version", "本体模型版本表");
        comments.put("om_object_type", "本体对象类型表");
        comments.put("om_property", "本体属性定义表");
        comments.put("om_object_identifier", "对象业务标识定义表");
        comments.put("om_link_type", "本体关系类型表");
        comments.put("om_mapping_rule", "源表字段到本体属性映射规则表");
        comments.put("om_term_root", "标准词根表");
        comments.put("om_standard_field", "标准字段表");
        comments.put("om_root_field_map", "词根与标准字段关系表");
        comments.put("om_root_synonym", "词根同义关系表");
        comments.put("om_schedule_config", "T+1调度配置表");
        comments.put("om_etl_watermark", "ETL增量水位表");
        comments.put("om_etl_run_log", "ETL运行日志表");
        comments.put("om_quality_result", "数据质量结果表");
        comments.put("om_query_history", "语义问数历史表");
        comments.put("om_change_log", "平台变更审计日志表");
        comments.put("om_object_instance", "本体对象实例表");
        comments.put("om_relation_instance", "本体关系实例表");
        comments.put("om_action_event", "本体动作事件表");
        return comments;
    }

    private static Map<String, String> columnComments() {
        Map<String, String> comments = new LinkedHashMap<>();
        comments.put("id", "主键");
        comments.put("tenant_id", "租户ID");
        comments.put("tenant_code", "租户编码");
        comments.put("tenant_name", "租户名称");
        comments.put("status", "状态枚举：ACTIVE=启用/生效，INACTIVE=停用，DRAFT=草稿，PENDING=待处理，SUCCESS=成功，FAILED=失败，ERROR=错误");
        comments.put("created_at", "创建时间");
        comments.put("updated_at", "更新时间");
        comments.put("source_code", "数据源编码");
        comments.put("source_name", "数据源名称");
        comments.put("db_type", "数据库类型枚举：DM=达梦，MYSQL=MySQL，POLARDB=PolarDB，H2=本地测试库");
        comments.put("db_version", "数据库版本");
        comments.put("charset_name", "字符集名称");
        comments.put("host", "数据库实例地址");
        comments.put("port", "数据库端口");
        comments.put("db_name", "数据库名");
        comments.put("schema_name", "Schema名称");
        comments.put("username", "连接用户名");
        comments.put("secret_ref", "密码引用类型：EMPTY=未配置，STATE_PASSWORD=平台状态快照保存，PLAIN_PASSWORD=本表保存明文密码");
        comments.put("password_value", "连接密码明文，轻量版供后续连接使用；接口与日志不展示");
        comments.put("max_concurrency", "最大并发数");
        comments.put("health_check_cron", "健康巡检Cron表达式");
        comments.put("allowed_schemas_json", "允许访问的Schema白名单JSON");
        comments.put("last_health_time", "最近健康检查时间");
        comments.put("source_id", "数据源ID");
        comments.put("check_time", "检查时间");
        comments.put("health_status", "健康状态枚举：OK=正常，ERROR=错误，UNKNOWN=未知，TIMEOUT=超时，AUTH_FAILED=认证失败，CONNECTION_REFUSED=连接拒绝");
        comments.put("error_code", "错误码");
        comments.put("error_message", "错误信息");
        comments.put("response_ms", "响应耗时毫秒");
        comments.put("compute_resource_id", "计算资源ID");
        comments.put("resource_code", "计算资源编码");
        comments.put("resource_name", "计算资源名称");
        comments.put("store_type", "存储类型枚举：META=元数据库，INSTANCE=实例数据库");
        comments.put("database_name", "数据库名或逻辑库名");
        comments.put("jdbc_url", "JDBC连接串");
        comments.put("health_message", "健康检查结果信息");
        comments.put("initialized", "初始化标志枚举：0=未初始化，1=已初始化");
        comments.put("active", "当前生效标志枚举：0=非当前，1=当前生效");
        comments.put("version_no", "模型版本号");
        comments.put("version_label", "模型版本标签");
        comments.put("is_latest", "最新版本标志枚举：0=否，1=是");
        comments.put("effective_at", "生效时间");
        comments.put("effective_by", "生效操作人");
        comments.put("rollback_from_id", "回滚来源版本ID");
        comments.put("impact_summary_json", "影响分析摘要JSON");
        comments.put("note", "备注");
        comments.put("model_version_id", "模型版本ID");
        comments.put("object_code", "对象编码");
        comments.put("api_name", "API名称");
        comments.put("display_name", "显示名称");
        comments.put("plural_display_name", "复数显示名称");
        comments.put("description", "描述");
        comments.put("domain_name", "业务域名称");
        comments.put("title_property_code", "标题属性编码");
        comments.put("primary_key_policy", "主键策略枚举：BUSINESS_KEY=业务主键，SURROGATE_KEY=代理主键");
        comments.put("confidence_score", "置信度分数");
        comments.put("source_ref_json", "来源引用JSON");
        comments.put("object_type_id", "对象类型ID");
        comments.put("property_code", "属性编码");
        comments.put("data_type", "数据类型枚举：STRING=字符串，DECIMAL=数值，INTEGER=整数，DATE=日期，DATETIME=日期时间，BOOLEAN=布尔，JSON=JSON");
        comments.put("is_required", "必填标志枚举：0=否，1=是");
        comments.put("is_unique", "唯一标志枚举：0=否，1=是");
        comments.put("is_searchable", "可检索标志枚举：0=否，1=是");
        comments.put("is_title", "标题字段标志枚举：0=否，1=是");
        comments.put("enum_values_json", "枚举值JSON");
        comments.put("default_value", "默认值");
        comments.put("format_pattern", "格式模式");
        comments.put("identifier_name", "标识名称");
        comments.put("property_codes", "属性编码列表");
        comments.put("is_primary", "主标识标志枚举：0=否，1=是");
        comments.put("link_code", "关系编码");
        comments.put("source_object_id", "源对象ID");
        comments.put("target_object_id", "目标对象ID");
        comments.put("cardinality", "基数枚举：1:1=一对一，1:N=一对多，N:1=多对一，N:N=多对多");
        comments.put("link_category", "关系类别枚举：UNKNOWN=未知，OWNERSHIP=归属，REFERENCE=引用，FLOW=流转，HIERARCHY=层级");
        comments.put("is_directional", "有向关系标志枚举：0=无向，1=有向");
        comments.put("reverse_api_name", "反向API名称");
        comments.put("reverse_display_name", "反向显示名称");
        comments.put("property_id", "属性ID");
        comments.put("source_schema", "源Schema");
        comments.put("source_table", "源表名");
        comments.put("source_column", "源字段名");
        comments.put("transform_expr", "转换表达式");
        comments.put("increment_strategy", "增量策略枚举：A=按更新时间，B=按自增主键，C=全量快照");
        comments.put("biz_key_flag", "业务键标志枚举：0=否，1=是");
        comments.put("mapping_status", "映射状态枚举：PENDING=待确认，ACTIVE=生效，INACTIVE=停用，REJECTED=驳回");
        comments.put("last_modeled_at", "最近建模时间");
        comments.put("root_code", "词根编码");
        comments.put("root_name", "词根名称");
        comments.put("biz_definition", "业务定义");
        comments.put("source_type", "来源类型枚举：MANUAL=人工，SCAN=扫描，AI_SKILL=AI技能生成，LLM=模型生成，IMPORT=导入");
        comments.put("confidence_level", "置信等级枚举：L1=高，L2=中，L3=低，CANDIDATE_HIGH=高置信候选，CANDIDATE_LOW=低置信候选，PENDING_REVIEW=待人工复核");
        comments.put("evidence_json", "证据JSON");
        comments.put("review_note", "评审备注");
        comments.put("field_code", "标准字段编码");
        comments.put("field_name", "标准字段名称");
        comments.put("field_type", "标准字段类型");
        comments.put("combo_key", "组合键");
        comments.put("root_id", "词根ID");
        comments.put("standard_field_id", "标准字段ID");
        comments.put("relation_type", "关系类型枚举：SYNONYM=同义，COMPOSED_OF=组成，RELATED=相关");
        comments.put("weight_score", "权重分数");
        comments.put("synonym_root_id", "同义词根ID");
        comments.put("schedule_code", "调度编码");
        comments.put("schedule_name", "调度名称");
        comments.put("cron_expr", "Cron表达式");
        comments.put("schedule_scope", "调度范围枚举：ALL=全部，SOURCE=数据源，OBJECT=对象");
        comments.put("scope_ref_id", "范围引用ID");
        comments.put("timezone", "时区");
        comments.put("run_mode", "运行模式枚举：AUTO=自动，MANUAL=手动");
        comments.put("strategy_type", "增量策略枚举：A=按更新时间，B=按自增主键，C=全量快照");
        comments.put("watermark_value", "水位值");
        comments.put("biz_date", "业务日期");
        comments.put("last_success_time", "最近成功时间");
        comments.put("job_id", "作业ID");
        comments.put("job_type", "作业类型枚举：ETL=同步，QUALITY=质量，MODEL=建模");
        comments.put("start_time", "开始时间");
        comments.put("end_time", "结束时间");
        comments.put("duration_ms", "持续时长毫秒");
        comments.put("retry_count", "重试次数");
        comments.put("ext_json", "扩展信息JSON");
        comments.put("metric_code", "指标编码");
        comments.put("metric_value", "指标值");
        comments.put("threshold_value", "阈值");
        comments.put("pass_flag", "通过标志枚举：0=未通过，1=通过");
        comments.put("severity", "严重级别枚举：INFO=提示，WARN=警告，ERROR=错误，FATAL=严重");
        comments.put("detail_json", "明细JSON");
        comments.put("query_id", "查询ID");
        comments.put("request_id", "请求ID");
        comments.put("raw_question", "原始问题");
        comments.put("generated_sql", "生成SQL");
        comments.put("edited_sql", "编辑后SQL");
        comments.put("executed_sql", "实际执行SQL");
        comments.put("execution_ms", "执行耗时毫秒");
        comments.put("result_rows", "结果行数");
        comments.put("user_feedback", "用户反馈枚举：LIKE=满意，DISLIKE=不满意，NONE=无反馈");
        comments.put("change_type", "变更类型枚举：CREATE=新增，UPDATE=更新，DELETE=删除，PUBLISH=发布，ROLLBACK=回滚，ACTIVATE=激活，INITIALIZE=初始化");
        comments.put("entity_type", "实体类型");
        comments.put("entity_id", "实体ID");
        comments.put("entity_code", "实体编码");
        comments.put("before_json", "变更前JSON");
        comments.put("after_json", "变更后JSON");
        comments.put("reason_code", "原因编码");
        comments.put("operator_id", "操作人ID");
        comments.put("operator_name", "操作人名称");
        comments.put("op_time", "操作时间");
        comments.put("trace_id", "链路追踪ID");
        comments.put("object_api_name", "对象API名称");
        comments.put("instance_id", "实例ID");
        comments.put("display_label", "展示标签");
        comments.put("properties_json", "实例属性JSON");
        comments.put("batch_id", "批次ID");
        comments.put("is_deleted", "删除标志枚举：0=未删除，1=已删除");
        comments.put("link_type_id", "关系类型ID");
        comments.put("link_api_name", "关系API名称");
        comments.put("source_object_type_id", "源对象类型ID");
        comments.put("source_instance_id", "源实例ID");
        comments.put("target_object_type_id", "目标对象类型ID");
        comments.put("target_instance_id", "目标实例ID");
        comments.put("start_date", "开始日期");
        comments.put("end_date", "结束日期");
        comments.put("event_id", "事件ID");
        comments.put("object_instance_id", "对象实例ID");
        comments.put("action_type_id", "动作类型ID");
        comments.put("event_type", "事件类型枚举：CREATE=创建，UPDATE=更新，DELETE=删除，STATUS_CHANGE=状态变更");
        comments.put("event_time", "事件时间");
        comments.put("changed_fields_json", "变更字段JSON");
        return comments;
    }
}
