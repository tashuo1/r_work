package com.ontology.lite.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontology.lite.model.PlatformModels.*;
import com.ontology.lite.service.OntologyPlatformService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ontology-persistence-test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always"
})
class MyBatisOntologyDataMapperTest {
    @Autowired
    private OntologyPlatformService service;

    @Autowired
    private PersistentPlatformStateMapper stateMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void serviceWritesMutationsToDatabaseState() {
        service.saveTermRoot(new TermRootRequest("ceshi", "测试", "通用", "持久化验证词根"));

        String payload = stateMapper.selectPayload(MyBatisOntologyDataMapper.STATE_KEY);

        assertThat(payload).contains("\"ceshi\"");
        assertThat(payload).contains("持久化验证词根");
    }

    @Test
    void serviceWritesComputeResourcesToDatabaseState() {
        var resource = service.createComputeResource(new ComputeResourceRequest(
            "COMPUTE_PERSIST",
            "持久化计算资源",
            "MYSQL",
            "127.0.0.1",
            3306,
            "root",
            "secret",
            "jdbc:h2:mem:compute-persist;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "ontology_meta",
            "ontology_instance"
        ));

        String payload = stateMapper.selectPayload(MyBatisOntologyDataMapper.STATE_KEY);

        assertThat(payload).contains("COMPUTE_PERSIST");
        assertThat(payload).contains("ontology_meta");
        assertThat(payload).contains("ontology_instance");

        Integer resourceRows = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM om_compute_resource
            WHERE compute_resource_id = ? AND resource_code = ?
              AND tenant_id = 1
              AND store_type IN ('META', 'INSTANCE')
              AND database_name IN ('ontology_meta', 'ontology_instance')
              AND username = 'root'
              AND password_value = 'secret'
            """, Integer.class, resource.id(), "COMPUTE_PERSIST");

        assertThat(resourceRows).isEqualTo(2);
    }

    @Test
    void serviceWritesComputeResourceHealthToDatabaseTable() {
        var resource = service.createComputeResource(new ComputeResourceRequest(
            "COMPUTE_HEALTH",
            "健康记录计算资源",
            "MYSQL",
            "127.0.0.1",
            3306,
            "root",
            "secret",
            "jdbc:h2:mem:compute-health;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "ontology_meta",
            "ontology_instance"
        ));

        service.testComputeResource(resource.id());

        Integer healthRows = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM om_compute_resource_health
            WHERE compute_resource_id = ? AND tenant_id = 1 AND store_type IN ('META', 'INSTANCE') AND health_status = 'OK'
            """, Integer.class, resource.id());

        assertThat(healthRows).isEqualTo(2);
    }

    @Test
    void schemaDoesNotCreateLegacyPlatformStateTable() {
        Integer tableCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_NAME = 'ontology_platform_state'
            """, Integer.class);

        assertThat(tableCount).isZero();
    }

    @Test
    void mapperLoadsPreviouslyPersistedStateFromDatabase() throws Exception {
        List<ObjectType> objects = List.of(new ObjectType(
            21,
            "test_object",
            "test_object",
            "测试对象",
            "通用",
            "test_table",
            "ACTIVE",
            9.1,
            List.of(new Property(22, "test_name", "test_name", "测试名称", "STRING", "name", "ceshi_mingcheng", 9.3))
        ));
        List<LinkType> links = List.of(new LinkType(23, "test_link", "test_link", "测试关系", "test_object", "test_object", "ACTIVE", 9.0));
        OntologyPlatformState state = new OntologyPlatformState(
            200,
            List.of(new DataSource(10, "DM_TEST", "测试库", "测试域", "DM", "jdbc:dm://127.0.0.1:5236/TEST", "dm.jdbc.driver.DmDriver", "127.0.0.1", 5236, "TEST", "readonly", true, "ACTIVE", true, 4, "OK", "ready", Instant.now())),
            Map.of(10L, "secret"),
            List.of(new TermRoot(11, "ceshi", "测试", "通用", "持久化验证词根", "ACTIVE", Instant.now())),
            List.of(),
            List.of(new ModelVersion(20, "V-test", "ACTIVE", true, 1, 1, 1, 1.0, Instant.now(), Instant.now())),
            Map.of(20L, objects),
            Map.of(20L, links),
            List.of(),
            List.of(),
            List.of(),
            new ScheduleConfig("03:10", "ACTIVE", 4, List.of("对象抽取", "关系构建", "质量门禁", "快照发布"), Instant.now()),
            List.of(new ComputeResource(30, "COMPUTE_LOAD", "加载计算资源", "MYSQL", "127.0.0.1", 3306, "root", true, "ontology_meta", "ontology_instance", "jdbc:mysql://127.0.0.1:3306/mysql", "ACTIVE", "OK", "ready", true, true, Instant.now())),
            Map.of(30L, "compute-secret"),
            30L
        );
        stateMapper.upsertPayload(MyBatisOntologyDataMapper.STATE_KEY, objectMapper.writeValueAsString(state));

        MyBatisOntologyDataMapper mapper = new MyBatisOntologyDataMapper(stateMapper, objectMapper);

        assertThat(mapper.termRoots().values()).extracting(TermRoot::code).containsExactly("ceshi");
        assertThat(mapper.t1Schedule().runAt()).isEqualTo("03:10");
        assertThat(mapper.ids().get()).isEqualTo(200);
        assertThat(mapper.dataSources().get(10L).jdbcUrl()).isEqualTo("jdbc:dm://127.0.0.1:5236/TEST");
        assertThat(mapper.dataSources().get(10L).passwordConfigured()).isTrue();
        assertThat(mapper.dataSourcePasswords().get(10L)).isEqualTo("secret");
        assertThat(mapper.versionObjects().get(20L).get(0).properties().get(0).apiName()).isEqualTo("test_name");
        assertThat(mapper.versionLinks().get(20L).get(0).apiName()).isEqualTo("test_link");
        assertThat(mapper.computeResources().get(30L).code()).isEqualTo("COMPUTE_LOAD");
        assertThat(mapper.computeResourcePasswords().get(30L)).isEqualTo("compute-secret");
        assertThat(mapper.activeComputeResourceId()).isEqualTo(30L);
        Integer computeStoreRows = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM om_compute_resource
            WHERE compute_resource_id = ? AND tenant_id = 1 AND store_type IN ('META', 'INSTANCE') AND password_value = ?
            """, Integer.class, 30L, "compute-secret");
        assertThat(computeStoreRows).isEqualTo(2);
    }
}
