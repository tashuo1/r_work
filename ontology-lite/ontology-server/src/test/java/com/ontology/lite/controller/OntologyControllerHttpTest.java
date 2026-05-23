package com.ontology.lite.controller;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ontology-controller-test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always"
})
class OntologyControllerHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void dashboardEndpointReturnsPlatformSummary() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.dataSourceCount").value(3))
            .andExpect(jsonPath("$.data.lastEtlStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.computeResourceStatus").exists())
            .andExpect(jsonPath("$.data.metaStoreStatus").exists())
            .andExpect(jsonPath("$.data.instanceStoreStatus").exists());
    }

    @Test
    void queryEndpointRejectsBlankSqlOverride() throws Exception {
        mockMvc.perform(post("/api/v1/internal/query/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"审计","sqlOverride":"   "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(content().string(containsString("SQL不能为空")));
    }

    @Test
    void queryEndpointRejectsStackedSql() throws Exception {
        mockMvc.perform(post("/api/v1/internal/query/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"审计","sqlOverride":"SELECT * FROM ontology_instance.employee; DROP TABLE om_object"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(content().string(containsString("禁止多语句")));
    }

    @Test
    void datasourceEndpointDeletesDatasource() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/internal/datasources")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"DM_DELETE_TEST","name":"待删除测试库","domain":"测试域","dbType":"DM","host":"127.0.0.1","port":5236,"databaseName":"DELETE_TEST","username":"readonly","password":"secret"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.code").value("DM_DELETE_TEST"))
            .andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(delete("/api/v1/internal/datasources/{id}", id.longValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.code").value("DM_DELETE_TEST"));

        mockMvc.perform(get("/api/v1/internal/datasources"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("DM_FINANCE")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("DM_DELETE_TEST"))));
    }

    @Test
    void computeResourceEndpointsCreateListInitializeAndActivate() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/internal/compute-resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"COMPUTE_HTTP","name":"HTTP计算资源","dbType":"MYSQL","host":"127.0.0.1","port":3306,"username":"root","password":"secret","jdbcUrl":"jdbc:h2:mem:compute-http-test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1","metaDatabaseName":"ontology_meta","instanceDatabaseName":"ontology_instance"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.code").value("COMPUTE_HTTP"))
            .andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/internal/compute-resources/{id}/initialize", id.longValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.success").value(true))
            .andExpect(jsonPath("$.data.createdTables").value(24));

        mockMvc.perform(post("/api/v1/internal/compute-resources/{id}/activate", id.longValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(true));

        mockMvc.perform(get("/api/v1/internal/compute-resources"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("COMPUTE_HTTP")));

        mockMvc.perform(get("/api/v1/internal/compute-resources/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("COMPUTE_HTTP"));
    }

    @Test
    void dataStandardGenerationAndReviewEndpointsWork() throws Exception {
        MvcResult generated = mockMvc.perform(post("/api/v1/internal/dictionary/ai-generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dataSourceIds":[1,2]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.skillName").value("cizhu-shengcheng"))
            .andExpect(jsonPath("$.data.executionMode").value("LOCAL_SKILL_SERVICE"))
            .andExpect(jsonPath("$.data.skillPath").exists())
            .andExpect(jsonPath("$.data.warnings[0]").value("真实 LLM 执行器未配置，当前使用本地 Skill 服务适配器"))
            .andExpect(jsonPath("$.data.createdRootCount").isNumber())
            .andReturn();

        mockMvc.perform(get("/api/v1/internal/dictionary/term-roots"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("AI_SKILL")))
            .andExpect(content().string(containsString("DRAFT")));

        Number rootId = JsonPath.read(generated.getResponse().getContentAsString(), "$.data.createdRootCount");
        org.assertj.core.api.Assertions.assertThat(rootId.intValue()).isGreaterThan(0);

        MvcResult roots = mockMvc.perform(get("/api/v1/internal/dictionary/term-roots"))
            .andReturn();
        List<Map<String, Object>> rootRows = JsonPath.read(roots.getResponse().getContentAsString(), "$.data");
        Number draftRootId = rootRows.stream()
            .filter(row -> "DRAFT".equals(row.get("status")))
            .map(row -> (Number) row.get("id"))
            .findFirst()
            .orElseThrow();

        mockMvc.perform(post("/api/v1/internal/dictionary/term-roots/{id}/approve", draftRootId.longValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"operator":"admin","remark":"HTTP确认"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.reviewNote").value("HTTP确认"));
    }
}
