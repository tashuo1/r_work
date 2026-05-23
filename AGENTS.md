# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 项目定位

本体论数据平台一期 — 大模型驱动的本体论大数据平台，参考 Palantir Foundry 架构。
工作空间：`/Users/wangchaojie/ai/ontology_woks/`

当前阶段：**一期 Spring Boot 分层单体已替代旧微服务基座，后续开发以 `ontology-lite` 为准。**

## 工作空间结构

```
ontology_woks/                    ← 项目根（设计文档 + 代码）
├── docs/superpowers/plans/       ← 1-5 产品/架构/技术/WBS/决策文档
├── docs/superpowers/specs/       ← Agent Team 设计
├── ontology-lite/ontology-server/ ← 当前 Spring Boot 分层单体 + 静态产品界面
└── start-ontology-platform.sh     ← 一键构建/启动/重启脚本
```

## 当前实现原则

- 只维护 `ontology-lite/ontology-server`。
- 不恢复旧微服务工程，不引入不必要的中间件。
- 当前版本为单进程 Spring Boot 应用，不依赖外部中间件或独立前端仓库。
- 当前运行配置从 `ontology-lite/runtime-config/ontology-platform.env` 读取；平台持久化库优先使用 MySQL/PolarDB，启动脚本会在外部库不可达时自动切换到本地 H2 兜底，保证一期产品页面可演示。
- 一期目标是快速验证业务闭环：数据源、本体建模、词典、T+1、问数、OpenAPI、验收清单。

## 常用命令

```bash
# 运行后端测试
cd /Users/wangchaojie/ai/ontology_woks/ontology-lite/ontology-server
mvn test

# 一键启动 / 重启 / 停止 / 查看状态
cd /Users/wangchaojie/ai/ontology_woks
./start-ontology-platform.sh start
./start-ontology-platform.sh restart
./start-ontology-platform.sh stop
./start-ontology-platform.sh status

# 访问产品界面
open http://127.0.0.1:9000/
```

## 代码架构

**基础框架：**
- Java 17
- Spring Boot 3 分层单体服务
- MyBatis 数据访问层
- MySQL/PolarDB 平台持久化库（外部库不可达时 H2 自动兜底）
- Maven 构建
- 静态 HTML/CSS/JavaScript 产品界面
- 演示数据与运行状态已落库持久化

**后端分层：**
- `controller`：REST API 入口，仅做 HTTP 入参/出参。
- `service`：业务编排、规则校验、安全闸、调度和问数逻辑。
- `mapper`：MyBatis 持久化边界；运行时使用 `MyBatisOntologyDataMapper`，单元测试可使用 `InMemoryOntologyDataMapper`。
- `model`：请求、响应、领域记录模型。
- `common`：通用响应与共享基础能力。

**当前关键文件：**

| 文件 | 职责 |
|---|---|
| `ontology-lite/ontology-server/pom.xml` | Maven 工程定义 |
| `controller/OntologyController.java` | REST API 入口 |
| `service/OntologyPlatformService.java` | 一期业务能力编排 |
| `mapper/OntologyDataMapper.java` | 数据访问边界 |
| `mapper/MyBatisOntologyDataMapper.java` | 当前运行时持久化数据访问实现 |
| `mapper/PersistentPlatformStateMapper.java` | MyBatis 表访问接口 |
| `src/main/resources/schema.sql` | MyBatis 启动建表脚本，兼容 MySQL/H2 |
| `model/PlatformModels.java` | 平台数据模型 |
| `src/main/resources/static/index.html` | 产品界面结构 |
| `src/main/resources/static/styles.css` | 产品界面样式 |
| `src/main/resources/static/app.js` | 前端 API 调用与页面交互 |
| `start-ontology-platform.sh` | 一键启动脚本 |

## 当前产品能力

主产品界面：左侧 01-09 主功能菜单支持折叠/展开，折叠后保留编号入口并记住用户上次选择。

| 功能 | 当前状态 |
|---|---|
| 数据源管理 | 已实现：DataWorks 风格列表、数据源类型选择弹层、创建/编辑 DM 数据源表单、列表行仅保留编辑/删除、JDBC 列表展示隐藏查询参数并支持换行、表格按容器自适应且不显示底部横向滑动条、三库连接信息录入、真实 JDBC 连接测试、删除数据源、内部启停、页面手动巡检；MySQL/PolarDB 连接串自动补齐连接/读超时参数，前端 API 网络异常显示中文诊断；驱动类和默认参数前台不展示，认证文件管理和30分钟后台巡检先不做 |
| 本体建模 | 已实现：工作台式四子页（元数据扫描、候选建模、语义图谱、版本发布）、演示扫描、扫描失败快照降级提示、候选生成中即时反馈、候选生成、影响评估、页面内发布确认弹层、版本生效、回滚、语义图谱节点/关系详情联动；真实 `ontology-modeling` Skill 稍后接入 |
| 标准词典 | 已实现：词根、标准字段、绑定、CSV 导出；Excel 先不做 |
| T+1 同步 | 已实现：调度查看、时点更新、手动触发、质量结果；真实源库抽取和实例表 upsert 待补 |
| 语义问数 | 已实现：自然语言问数、SQL 安全闸、图表、CSV；结果来自演示实例数据 |
| OpenAPI | 已实现：本地无鉴权元数据、属性、实例查询；API 治理一期先不做 |
| 验收清单 | 已实现：`/api/v1/acceptance` |

## 一期硬约束

- 3个达梦源库（财务/投资/人事），T+1日批（01:00），不做实时CDC。
- SQL安全闸已冻结：仅SELECT + 自动LIMIT + timeout + EXPLAIN预检 + 禁跨源库join。
- 词典：词根=单一中文业务词的汉语拼音；标准字段=已登记规范词根的下划线组合；辅助识别词根只用于 `om_root_synonym`，不得参与标准字段组成。
- 一期不允许临时插入新业务域对象（走变更评审）。
- 重复率0%按物理主键判定，P0用例100%通过，P1>=90%。
- 完整决策：`5_疑问清单与决策记录.md` 第0节（19项）。

## 修改文档规则

| 修改目标 | 需同步 |
|---|---|
| `docs/superpowers/plans/1_平台架构设计方案.md` | 检查 3_(WBS)、5_(决策) 交叉引用 |
| `docs/superpowers/plans/4_技术规范（接口+数据库）.md` | 同步 API 示例和当前实现说明 |
| `docs/superpowers/plans/2_产品功能清单（一期）.md` | 同步 3_(WBS) 用例ID映射 |
| `创建skill 提示词.md` | 永远不改 |

## 开发要求

- 先想再写：先说明假设，不要凭空猜。
- 外科式修改：只改必须改的地方，别顺手扩大 PR。
- 先读再写：读 exports、调用方和共享工具。
- 匹配约定：沿用代码库模式，不擅自换范式。
- 简单优先：最少代码，拒绝投机式抽象。
- 目标驱动：先定义成功标准，再循环验证。
- 每步检查点：别在坏状态上继续完成后几步。
- 失败大声暴露：不要把不确定性藏进“成功完成”里。
- 实际犯过的错及时修正，并补充记录，下次不要犯。

## 产品到可用页面固定工作流

- 工作流文档：`docs/superpowers/plans/产品到可用页面固定工作流.md`
- 页面升级 PRD：`docs/superpowers/plans/本体平台产品功能页面升级PRD.md`
- 适用范围：产品功能、页面、工具、后台管理界面、SaaS 界面、交互应用。
- 固定顺序：产品判断 → 产品文档 → 范围审查 → 设计系统与交互 → 实现 → 审查与验收。
- 产品判断必须先回答：用户是谁、要完成什么任务、当前替代方案是什么、为什么值得做、成功指标是什么、哪些不做。
- 产品文档必须落到可测试产物：PRD、用户故事、验收标准、边界场景、空/加载/错误/权限失败状态、数据字段、权限、审计要求。
- 后台和 SaaS 页面默认克制、密集、可扫描；不做静态摆设，不做无主流程交互的视觉稿。
- 本体平台页面升级必须按 DataWorks 工作流和 Palantir Foundry Ontology 对标：DataWorks 负责数据集成、运维、质量、服务发布；Palantir 负责 Object / Property / Link / Action / Function / Role / Object View。
- UI 目标是 Apple 式克制和精密：低噪音、高层级清晰度、统一图标、明确主次危险操作，不用装饰性大色块或营销式 Hero 代替工作台。
- 目标用户和主流程不清楚，不进入设计和开发；验收标准不可测试，不进入实现。
- 完成后必须验证桌面端、移动端、主流程、空状态、加载状态、错误状态、权限或禁用状态、文字不重叠不溢出。
- 开发实现任务最终交付需说明：改了什么、改在哪里、如何验证、当前访问地址、剩余风险。

## Agent Team 开发

- 设计：`docs/superpowers/specs/2026-05-20-ontology-agent-team-design.md`
- 日志：`ontology-lite/runtime-logs/`
- 协议：Lead分配 → Dev编码 → UX前端 → QA测试 + Codex审查（安全/架构/性能/边界） → BugDev修复 → QA回归 → Lead验收
