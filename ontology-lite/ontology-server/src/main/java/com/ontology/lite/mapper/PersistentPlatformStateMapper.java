package com.ontology.lite.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import com.ontology.lite.model.PlatformModels.ComputeResource;

import java.util.List;

@Mapper
public interface PersistentPlatformStateMapper {
    @Select("SELECT payload FROM om_platform_state WHERE state_key = #{stateKey}")
    String selectPayload(@Param("stateKey") String stateKey);

    @Insert("""
        INSERT INTO om_platform_state (state_key, payload, updated_at)
        VALUES (#{stateKey}, #{payload}, CURRENT_TIMESTAMP)
        """)
    void insertPayload(@Param("stateKey") String stateKey, @Param("payload") String payload);

    @Update("""
        UPDATE om_platform_state
        SET payload = #{payload}, updated_at = CURRENT_TIMESTAMP
        WHERE state_key = #{stateKey}
        """)
    int updatePayload(@Param("stateKey") String stateKey, @Param("payload") String payload);

    @Delete("DELETE FROM om_platform_state WHERE state_key = #{stateKey}")
    void deletePayload(@Param("stateKey") String stateKey);

    @Delete("DELETE FROM om_compute_resource")
    void deleteComputeResources();

    @Delete("DELETE FROM om_compute_resource_health")
    void deleteComputeResourceHealth();

    @Insert("""
        INSERT INTO om_compute_resource (
          tenant_id, compute_resource_id, resource_code, resource_name, store_type, db_type, host, port,
          database_name, schema_name, username, secret_ref, password_value,
          jdbc_url, status, health_status,
          health_message, initialized, active, last_health_time, updated_at
        )
        VALUES (
          1, #{resource.id}, #{resource.code}, #{resource.name}, #{storeType}, #{resource.dbType}, #{resource.host}, #{resource.port},
          #{databaseName}, #{databaseName}, #{resource.username}, #{secretRef}, #{password},
          #{resource.jdbcUrl}, #{resource.status}, #{resource.healthStatus}, #{resource.healthMessage},
          #{resource.initialized}, #{resource.active}, #{resource.lastHealthTime}, CURRENT_TIMESTAMP
        )
        """)
    void insertComputeResource(
        @Param("resource") ComputeResource resource,
        @Param("storeType") String storeType,
        @Param("databaseName") String databaseName,
        @Param("secretRef") String secretRef,
        @Param("password") String password
    );

    @Insert("""
        INSERT INTO om_compute_resource_health (tenant_id, compute_resource_id, store_type, database_name, check_time, health_status, error_message, response_ms)
        VALUES (1, #{resource.id}, #{storeType}, #{databaseName}, #{resource.lastHealthTime}, #{resource.healthStatus}, #{resource.healthMessage}, 0)
        """)
    void insertComputeResourceHealth(
        @Param("resource") ComputeResource resource,
        @Param("storeType") String storeType,
        @Param("databaseName") String databaseName
    );

    default void upsertPayload(String stateKey, String payload) {
        if (updatePayload(stateKey, payload) == 0) {
            insertPayload(stateKey, payload);
        }
    }

    default void replaceComputeResources(List<ComputeResource> resources, java.util.Map<Long, String> passwords) {
        deleteComputeResourceHealth();
        deleteComputeResources();
        if (resources == null) {
            return;
        }
        for (ComputeResource resource : resources) {
            String password = passwords == null ? "" : passwords.getOrDefault(resource.id(), "");
            String secretRef = password == null || password.isBlank() ? "EMPTY" : "PLAIN_PASSWORD";
            insertComputeResource(resource, "META", resource.metaDatabaseName(), secretRef, password == null ? "" : password);
            insertComputeResource(resource, "INSTANCE", resource.instanceDatabaseName(), secretRef, password == null ? "" : password);
            if (resource.lastHealthTime() != null) {
                insertComputeResourceHealth(resource, "META", resource.metaDatabaseName());
                insertComputeResourceHealth(resource, "INSTANCE", resource.instanceDatabaseName());
            }
        }
    }
}
