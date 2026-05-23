package com.ontology.lite.service;

import com.ontology.lite.model.PlatformModels.ComputeResource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComputeResourceSchemaInitializerTest {
    private final ComputeResourceSchemaInitializer initializer = new ComputeResourceSchemaInitializer();

    @Test
    void mysqlDialectCreatesTwoDatabasesAndTwentyFourOntologyTables() {
        ComputeResource resource = resource("MYSQL");

        var plan = initializer.plan(resource);

        assertThat(plan.schemaStatements()).contains(
            "CREATE DATABASE IF NOT EXISTS `ontology_meta`",
            "CREATE DATABASE IF NOT EXISTS `ontology_instance`"
        );
        assertThat(plan.tableStatements()).hasSize(24);
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql).contains("`ontology_meta`.`om_data_source`"));
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql).contains("`ontology_meta`.`om_compute_resource`"));
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql).contains("`ontology_meta`.`om_compute_resource_health`"));
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql).contains("`ontology_instance`.`om_object_instance`"));
        assertThat(plan.tableStatements()).allSatisfy(sql -> assertThat(sql).contains("COMMENT='"));
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql)
            .contains("store_type VARCHAR(16) NOT NULL COMMENT '存储类型枚举：META=元数据库，INSTANCE=实例数据库'")
            .contains("password_value VARCHAR(512) NOT NULL COMMENT '连接密码明文，轻量版供后续连接使用；接口与日志不展示'")
            .contains("COMMENT='计算资源数据库连接信息表'"));
        assertThat(plan.migrationStatements()).contains(
            "ALTER TABLE `ontology_meta`.`om_compute_resource` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID' AFTER id",
            "ALTER TABLE `ontology_meta`.`om_compute_resource_health` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID' AFTER id"
        );
        assertThat(plan.commentStatements()).contains(
            "ALTER TABLE `ontology_meta`.`om_compute_resource` COMMENT = '计算资源数据库连接信息表'",
            "ALTER TABLE `ontology_meta`.`om_compute_resource` MODIFY COLUMN store_type VARCHAR(16) NOT NULL COMMENT '存储类型枚举：META=元数据库，INSTANCE=实例数据库'",
            "ALTER TABLE `ontology_meta`.`om_compute_resource` MODIFY COLUMN password_value VARCHAR(512) NOT NULL COMMENT '连接密码明文，轻量版供后续连接使用；接口与日志不展示'"
        );
    }

    @Test
    void dmDialectCreatesTwoSchemasAndTwentyFourOntologyTables() {
        ComputeResource resource = resource("DM");

        var plan = initializer.plan(resource);

        assertThat(plan.schemaStatements()).contains(
            "CREATE SCHEMA IF NOT EXISTS ontology_meta",
            "CREATE SCHEMA IF NOT EXISTS ontology_instance"
        );
        assertThat(plan.tableStatements()).hasSize(24);
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql).contains("ontology_meta.om_data_source"));
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql).contains("ontology_meta.om_compute_resource"));
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql).contains("ontology_meta.om_compute_resource_health"));
        assertThat(plan.tableStatements()).anySatisfy(sql -> assertThat(sql).contains("ontology_instance.om_action_event"));
        assertThat(plan.commentStatements()).contains(
            "COMMENT ON TABLE ontology_meta.om_compute_resource IS '计算资源数据库连接信息表'",
            "COMMENT ON COLUMN ontology_meta.om_compute_resource.store_type IS '存储类型枚举：META=元数据库，INSTANCE=实例数据库'",
            "COMMENT ON COLUMN ontology_meta.om_compute_resource.password_value IS '连接密码明文，轻量版供后续连接使用；接口与日志不展示'"
        );
        assertThat(plan.migrationStatements()).contains(
            "ALTER TABLE ontology_meta.om_compute_resource ADD tenant_id BIGINT DEFAULT 1 NOT NULL",
            "ALTER TABLE ontology_meta.om_compute_resource_health ADD tenant_id BIGINT DEFAULT 1 NOT NULL"
        );
    }

    @Test
    void tableNamesMatchRuntimeDialectPrefixes() {
        ComputeResource mysql = resource("MYSQL");
        ComputeResource dm = resource("DM");
        ComputeResource h2 = new ComputeResource(
            1,
            "COMPUTE_TEST",
            "测试计算资源",
            "MYSQL",
            "127.0.0.1",
            3306,
            "sa",
            false,
            "ontology_meta",
            "ontology_instance",
            "jdbc:h2:mem:compute-prefix-test;MODE=MySQL;DATABASE_TO_UPPER=false",
            "ACTIVE",
            "OK",
            "ready",
            true,
            true,
            Instant.now()
        );

        assertThat(initializer.tableNames(mysql).meta("om_data_source")).isEqualTo("`ontology_meta`.`om_data_source`");
        assertThat(initializer.tableNames(mysql).meta("om_compute_resource")).isEqualTo("`ontology_meta`.`om_compute_resource`");
        assertThat(initializer.tableNames(mysql).instance("om_object_instance")).isEqualTo("`ontology_instance`.`om_object_instance`");
        assertThat(initializer.tableNames(dm).meta("om_data_source")).isEqualTo("ontology_meta.om_data_source");
        assertThat(initializer.tableNames(dm).meta("om_compute_resource")).isEqualTo("ontology_meta.om_compute_resource");
        assertThat(initializer.tableNames(dm).instance("om_object_instance")).isEqualTo("ontology_instance.om_object_instance");
        assertThat(initializer.tableNames(h2).meta("om_data_source")).isEqualTo("meta_om_data_source");
        assertThat(initializer.tableNames(h2).meta("om_compute_resource")).isEqualTo("meta_om_compute_resource");
        assertThat(initializer.tableNames(h2).instance("om_object_instance")).isEqualTo("instance_om_object_instance");
    }

    @Test
    void initializeSkipsMissingColumnCommentsForLegacyTables() throws SQLException {
        List<String> executedSql = new ArrayList<>();
        Connection connection = mysqlConnection(executedSql);

        var result = initializer.initialize(connection, resource("MYSQL"));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("缺失字段注释已跳过 1 条");
        assertThat(executedSql).anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE `ontology_meta`.`om_compute_resource` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID' AFTER id"));
        assertThat(executedSql).anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE `ontology_meta`.`om_compute_resource` COMMENT"));
        assertThat(executedSql).anySatisfy(sql -> assertThat(sql).contains("MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键'"));
        assertThat(executedSql).anySatisfy(sql -> assertThat(sql).contains("MODIFY COLUMN resource_code VARCHAR(64) NOT NULL COMMENT '计算资源编码'"));
    }

    private ComputeResource resource(String dbType) {
        return new ComputeResource(
            1,
            "COMPUTE_MAIN",
            "主计算资源",
            dbType,
            "127.0.0.1",
            dbType.equals("DM") ? 5236 : 3306,
            "root",
            true,
            "ontology_meta",
            "ontology_instance",
            dbType.equals("DM") ? "jdbc:dm://127.0.0.1:5236" : "jdbc:mysql://127.0.0.1:3306/mysql",
            "ACTIVE",
            "UNKNOWN",
            "待初始化",
            false,
            false,
            Instant.now()
        );
    }

    private Connection mysqlConnection(List<String> executedSql) {
        Statement statement = (Statement) Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, args) -> {
                if ("execute".equals(method.getName())) {
                    String sql = String.valueOf(args[0]);
                    executedSql.add(sql);
                    if (sql.contains("`ontology_meta`.`om_compute_resource` MODIFY COLUMN tenant_id")) {
                        throw new SQLException("Unknown column 'tenant_id' in 'om_compute_resource'", "42S22", 1054);
                    }
                    return false;
                }
                if ("close".equals(method.getName())) {
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        );
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
            DatabaseMetaData.class.getClassLoader(),
            new Class<?>[] {DatabaseMetaData.class},
            (proxy, method, args) -> "getDatabaseProductName".equals(method.getName()) ? "MySQL" : defaultValue(method.getReturnType())
        );
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if ("createStatement".equals(method.getName())) {
                    return statement;
                }
                if ("getMetaData".equals(method.getName())) {
                    return metaData;
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }
}
