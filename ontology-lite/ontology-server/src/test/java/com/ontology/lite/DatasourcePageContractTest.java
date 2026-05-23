package com.ontology.lite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourcePageContractTest {
    private static final Path STATIC_DIR = Path.of("src/main/resources/static");

    @Test
    void datasourcePageUsesDataworksListPickerAndDmCreateFlow() throws Exception {
        String index = Files.readString(STATIC_DIR.resolve("index.html"));
        String app = Files.readString(STATIC_DIR.resolve("app.js"));
        String css = Files.readString(STATIC_DIR.resolve("styles.css"));

        String pageContract = index + app + css;

        assertThat(index).contains("datasourceTabs", "datasourceToolbar", "datasourceModal", "datasourceCreatePanel");
        assertThat(pageContract).doesNotContain("认证文件管理", "datasourceCertPanel", "data-source-tab=\"cert\"");
        assertThat(app).contains("renderDatasourceList", "openDatasourcePicker", "openDatasourceForm", "renderDatasourceTypeGrid");
        assertThat(pageContract).contains("创建DM数据源", "常用数据源", "JDBC 连接串预览");
        assertThat(css).contains(".datasource-workbench", ".datasource-modal", ".datasource-create-panel", ".datasource-type-grid");
    }

    @Test
    void datasourceFormHidesBackendOnlyDriverAndDefaultParameters() throws Exception {
        String app = Files.readString(STATIC_DIR.resolve("app.js"));

        assertThat(app).doesNotContain("for=\"sourceDriver\"", "id=\"sourceDriver\"", "驱动类：");
        assertThat(app).doesNotContain("for=\"sourceConcurrency\"", "id=\"sourceConcurrency\"", "高级参数：", "默认参数：");
        assertThat(app).doesNotContain("driverClassName: document.querySelector('#sourceDriver')");
        assertThat(app).doesNotContain("maxConcurrency: Number(document.querySelector('#sourceConcurrency')");
    }

    @Test
    void datasourceListOnlyShowsEditDeleteAndHidesJdbcQueryParameters() throws Exception {
        String app = Files.readString(STATIC_DIR.resolve("app.js"));
        String css = Files.readString(STATIC_DIR.resolve("styles.css"));

        assertThat(app).contains("function displayJdbcUrl", "data-delete-source", "DELETE");
        assertThat(app).doesNotContain("data-test-source", "data-toggle-source");
        assertThat(app).contains("split('?')[0]");
        assertThat(css).contains(".connection-cell", ".jdbc-preview");
        assertThat(css).contains("overflow-wrap: anywhere");
    }

    @Test
    void datasourceTableFitsContainerWithoutHorizontalScrollbar() throws Exception {
        String app = Files.readString(STATIC_DIR.resolve("app.js"));
        String css = Files.readString(STATIC_DIR.resolve("styles.css"));

        assertThat(app).contains("<colgroup>", "connection-col", "operation-col");
        assertThat(css).contains("table-layout: fixed");
        assertThat(css).contains("overflow-x: hidden");
        assertThat(css).doesNotContain("min-width: 1120px");
        assertThat(css).contains(".datasource-table td", "word-break: break-word");
    }

    @Test
    void apiRequestsConvertNetworkFailuresToChineseDiagnostics() throws Exception {
        String app = Files.readString(STATIC_DIR.resolve("app.js"));

        assertThat(app).contains("API_TIMEOUT_MS", "AbortController");
        assertThat(app).contains("无法连接本地服务", "请求超时");
        assertThat(app).doesNotContain("throw new Error(error.message)");
    }

    @Test
    void mysqlJdbcPreviewIncludesConnectionTimeoutsForRealSourceTests() throws Exception {
        String app = Files.readString(STATIC_DIR.resolve("app.js"));

        assertThat(app).contains("connectTimeout=10000", "socketTimeout=10000");
    }

    @Test
    void computeResourcePageUsesDatasourceStyleWorkbenchAndActions() throws Exception {
        String index = Files.readString(STATIC_DIR.resolve("index.html"));
        String app = Files.readString(STATIC_DIR.resolve("app.js"));
        String css = Files.readString(STATIC_DIR.resolve("styles.css"));

        assertThat(index).contains(
            "#compute-resources",
            "datasource-workbench compute-resource-workbench",
            "datasource-tabs",
            "datasource-toolbar",
            "datasource-list",
            "计算资源",
            "computeResourceTable",
            "computeResourceCreatePanel",
            "batchAddComputeResource",
            "inspectComputeResources"
        );
        assertThat(app).contains(
            "loadComputeResources",
            "renderComputeResourceList",
            "openComputeResourceForm",
            "/internal/compute-resources"
        );
        assertThat(app).contains(
            "data-activate-compute",
            "暂无计算资源",
            "数据源描述：",
            "<strong>生产环境</strong>",
            "保存后将自动测试连通性，连接成功后自动初始化平台库",
            "autoTestAndInitializeComputeResource",
            "compute-actions"
        );
        assertThat(app).doesNotContain("data-initialize-compute", "data-test-compute");
        assertThat(app).doesNotContain("testEditedComputeResource", "initializeEditedComputeResource");
        assertThat(css).contains(".compute-resource-workbench");
        assertThat(css).contains(".datasource-workbench");
        assertThat(css).contains(".compute-actions", "white-space: nowrap");
    }

    @Test
    void dictionaryPageIsRenamedToDataStandardsWithThreeSubPages() throws Exception {
        String index = Files.readString(STATIC_DIR.resolve("index.html"));
        String app = Files.readString(STATIC_DIR.resolve("app.js"));
        String css = Files.readString(STATIC_DIR.resolve("styles.css"));

        assertThat(index).contains(
            "数据标准",
            "data-standard-nav",
            "data-standard-pane=\"ai\"",
            "data-standard-pane=\"roots\"",
            "data-standard-pane=\"fields\"",
            "AI生成",
            "命名词根",
            "标准字段",
            "dataStandardSources",
            "generateDataStandards",
            "生成进度"
        );
        assertThat(app).contains(
            "loadDataStandards",
            "renderDataStandardSources",
            "generateDataStandards",
            "setDataStandardGenerationStatus",
            "data-standard-generation-progress",
            "data-standard-generation-card",
            "正在按 cizhu-shengcheng 输出结构生成候选",
            "本地 Skill 服务适配器",
            "程序内置 zip 解包安装",
            "executionMode",
            "skillPath",
            "viewGeneratedRoots",
            "standard-review-list",
            "standard-review-row",
            "/internal/dictionary/ai-generate",
            "data-approve-root",
            "data-reject-root",
            "data-approve-field",
            "data-reject-field"
        );
        assertThat(app).doesNotContain("standard-review-card");
        assertThat(css).contains(
            ".data-standard-layout",
            ".data-standard-side",
            ".standard-review-list",
            ".standard-review-row",
            ".data-standard-generation-card",
            ".data-standard-generation-steps",
            ".generation-runtime",
            ".generation-warning-list",
            ".loading-dot",
            ".similar-root-list"
        );
        assertThat(app).doesNotContain("真实 LLM/Skill 执行器暂未接入");
        assertThat(css).doesNotContain(".standard-review-card");
    }

    @Test
    void modelingPageUsesTaskFocusedWorkbenchWithFourStepDrilldown() throws Exception {
        String index = Files.readString(STATIC_DIR.resolve("index.html"));
        String app = Files.readString(STATIC_DIR.resolve("app.js"));
        String css = Files.readString(STATIC_DIR.resolve("styles.css"));

        assertThat(index).contains(
            "modelingWorkbench",
            "modelingHero",
            "modelingStageCard",
            "modelingNextActions",
            "modelingRiskPanel",
            "modelingStepRail",
            "modelingFocusArea",
            "data-modeling-tab=\"scan\"",
            "data-modeling-tab=\"candidates\"",
            "data-modeling-tab=\"graph\"",
            "data-modeling-tab=\"versions\"",
            "data-modeling-pane=\"scan\"",
            "data-modeling-pane=\"candidates\"",
            "data-modeling-pane=\"graph\"",
            "data-modeling-pane=\"versions\"",
            "semanticGraph",
            "modelDetailPanel"
        );
        assertThat(app).contains(
            "loadModelingWorkbench",
            "renderModelingCommandCenter",
            "deriveModelingCommandState",
            "renderModelingSteps",
            "renderModelingRisks",
            "switchModelingTab(state.recommendedTab)",
            "renderMetadataScanPane",
            "currentModelScanError",
            "扫描失败，使用快照",
            "源库连接恢复后再重新扫描",
            "renderCandidateModelPane",
            "renderSemanticGraph",
            "renderVersionReleasePane",
            "focusedModelVersionStatus",
            "modelDetailStatus",
            "modeling-stage-label",
            "setModelCandidateGenerationStatus",
            "正在生成对象、属性与关系候选",
            "openModelPublishConfirm",
            "confirmModelPublish",
            "switchModelingTab",
            "当前阶段",
            "下一步动作",
            "风险提示",
            "候选待发布",
            "/internal/modeling/candidates/generate",
            "/internal/modeling/versions"
        );
        assertThat(app).doesNotContain("confirm(`影响对象");
        assertThat(css).contains(
            ".modeling-workbench",
            ".modeling-hero",
            ".modeling-command-center",
            ".modeling-stage-card",
            ".modeling-next-actions",
            ".modeling-risk-panel",
            ".modeling-risk-panel.is-compact",
            ".modeling-step-rail",
            ".modeling-compact-metric",
            ".modeling-focus-area",
            ".modeling-pane",
            ".semantic-graph",
            ".model-node",
            ".model-edge",
            ".model-detail-panel",
            ".candidate-generation-state",
            ".model-publish-modal",
            "grid-template-columns: minmax(240px, 0.72fr) minmax(520px, 1.12fr) minmax(240px, 0.72fr)",
            "align-items: start",
            "min-height: 42px",
            "DataWorks density"
        );
    }

    @Test
    void mainFunctionMenuCanCollapseWithoutLosingNineEntries() throws Exception {
        String index = Files.readString(STATIC_DIR.resolve("index.html"));
        String app = Files.readString(STATIC_DIR.resolve("app.js"));
        String css = Files.readString(STATIC_DIR.resolve("styles.css"));

        assertThat(index).contains(
            "id=\"sidebarToggle\"",
            "aria-label=\"收起功能菜单\"",
            "aria-expanded=\"true\"",
            "data-nav-text"
        );
        assertThat(index).contains(
            "data-nav-text>工作台",
            "data-nav-text>数据源",
            "data-nav-text>计算资源",
            "data-nav-text>本体建模",
            "data-nav-text>数据标准",
            "data-nav-text>T+1 同步",
            "data-nav-text>语义问数",
            "data-nav-text>OpenAPI",
            "data-nav-text>验收清单"
        );
        assertThat(app).contains(
            "NAV_COLLAPSED_STORAGE_KEY",
            "initializeSidebarCollapse",
            "setSidebarCollapsed",
            "document.body.classList.toggle('nav-collapsed'",
            "localStorage.setItem(NAV_COLLAPSED_STORAGE_KEY"
        );
        assertThat(css).contains(
            "body.nav-collapsed",
            ".sidebar-toggle",
            ".nav-text",
            ".brand-copy",
            ".sidebar-toggle-icon"
        );
    }
}
