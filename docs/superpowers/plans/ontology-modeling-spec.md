# Ontology Modeling Engine — 可执行规格文档（V4.0）

> **目标读者**：AI 编码助手（Claude Code、Codex、Copilot 等），用于从头实现本体建模引擎。
> **版本**：V4.0，2026-05-22
> **对齐**：项目 22 表 DDL（`om_` 前缀），Palantir Foundry 三层架构

---

## 目录

1. [核心概念与设计哲学](#1-核心概念与设计哲学)
2. [输入输出契约](#2-输入输出契约)
3. [目标数据模型（22 表）](#3-目标数据模型22-表)
4. [配置格式（YAML）](#4-配置格式yaml)
5. [12 阶段流水线](#5-12-阶段流水线)
6. [推导规则详解](#6-推导规则详解)
7. [评分与门禁系统](#7-评分与门禁系统)
8. [安全硬门禁](#8-安全硬门禁)
9. [关键算法](#9-关键算法)
10. [输出产物清单](#10-输出产物清单)
11. [错误处理与降级](#11-错误处理与降级)

---

## 1. 核心概念与设计哲学

### 1.1 什么是本体建模引擎

将物理 MySQL 、达梦数据库自动映射为 **Type/Instance 分离** 的本体模型。输入是源库的 schema 和数据，输出是目标库中的本体元数据（对象类型、关系类型、动作类型、属性、映射规则）和实例数据。

### 1.2 不可突破的硬约束

| 约束 | 说明 |
|------|------|
| **只读源库** | 源库仅允许 `SELECT`/`SHOW`/`DESCRIBE`/`EXPLAIN`/`WITH`/`SET SESSION MAX_EXECUTION_TIME`。任何写入被硬拦截 |
| **先 Staging 后正式** | 候选数据必须先写入 staging 表（带 `run_id`），门禁通过后才能复制到正式表 |
| **反克隆** | 禁止 `INSERT INTO target SELECT ... FROM meta/mate` 或任何基线库→目标库的复制链路 |
| **门禁通过才写入** | full >= 95 且 blocker=0；incremental >= 92 且 blocker=0 |
| **先声明口径后对比** | 对账前必须声明 `comparison_scope`，未声明不得下结论 |

### 1.3 核心原则

- **Entity-First（实体优先）**：先识别所有独立实体，再构建关系，最后推导动作
- **Type 优先**：先定义 object/link/action/property type，再构建 mapping_rule 和 rule，最后生成 instance
- **编码与展示分离**：`code` 类字段使用英文编码；`display_name`/`description` 使用中文
- **全量优先采集**：一次性批量获取元数据，避免逐表串行查询
- **目标结构自适配**：根据目标库真实列结构动态写入，不硬编码列名

---

## 2. 输入输出契约

### 2.1 输入类型与降级顺序

```
MySQL CLI→jddbc → DDL 文件解析 → Excel/ETS 数据目录
```

### 2.2 输入：MySQL\\达梦 源库

通过 pymysql 连接，最小权限 `SELECT`。需要以下元数据：
- `information_schema.tables`：表名、注释、行数、表类型
- `information_schema.columns`：列名、类型、注释、是否可空、键类型
- `information_schema.KEY_COLUMN_USAGE`：外键关系
- `information_schema.TABLE_CONSTRAINTS`：约束信息
- 可选 `catalog_field` 表（部分业务库有，可替代 information_schema.columns）

### 2.3 输出：目标库（22 张表）

类型层（meta 库 7 表）+ 映射层（meta 库 2 表）+ 治理层（meta 库 4 表）+ Staging 表（7 张）+ 文件产物。

---

## 3. 目标数据模型（22 表）

### 3.1 Type 层 — meta 库

#### om_object_type（对象类型定义）

| 列 | 类型 | 必填 | 说明 |
|----|------|------|------|
| id | BIGINT | 自动 | 自增主键 |
| tenant_id | BIGINT | 是 | 租户ID，默认 1 |
| model_version_id | BIGINT | 是 | 关联 om_model_version.id |
| object_code | VARCHAR(100) | 是 | 英文编码，`^[a-z][a-z0-9_]*$`，禁止 t_/tb_/tbl_ 前缀 |
| api_name | VARCHAR(100) | 是 | PascalCase，`^[A-Z][a-zA-Z0-9]*$` |
| display_name | VARCHAR(200) | 是 | 中文显示名 |
| plural_display_name | VARCHAR(200) | | 复数显示名（如"企业列表"） |
| description | TEXT | | 描述 |
| domain_name | VARCHAR(50) | 是 | 枚举：finance/investment/hr/cross_domain |
| status | VARCHAR(20) | 是 | 枚举：DRAFT/ACTIVE/INACTIVE，默认 DRAFT |
| title_property_code | VARCHAR(100) | | 标题属性 code |
| primary_key_policy | VARCHAR(50) | 是 | 枚举：BUSINESS_KEY/AUTO_INCREMENT/UUID |
| confidence_score | DECIMAL(3,1) | | 置信度 0.0-10.0 |
| source_ref_json | JSON | | 来源追溯：{"schema":"","table":"","source_id":N} |

#### om_property（属性定义）

| 列 | 类型 | 必填 | 说明 |
|----|------|------|------|
| object_type_id | BIGINT | 是 | 所属对象类型 |
| property_code | VARCHAR(100) | 是 | 格式：`{object_code}_{field_snake}` |
| api_name | VARCHAR(100) | 是 | camelCase |
| display_name | VARCHAR(200) | 是 | 中文 |
| data_type | VARCHAR(50) | 是 | string/text/bigint/decimal/double/date/datetime/bool/json |
| is_required | TINYINT | | NOT NULL → 1 |
| is_unique | TINYINT | | PRI/UNI → 1 |
| is_searchable | TINYINT | | 默认 1 |
| is_title | TINYINT | | 标题字段 → 1 |
| enum_values_json | JSON | | 枚举值：[{"value":"","label":"","count":N}] |
| source_ref_json | JSON | | {"schema":"","table":"","column":""} |
| confidence_score | DECIMAL(3,1) | | |

#### om_object_identifier（对象标识规则）

| 列 | 类型 | 说明 |
|----|------|------|
| identifier_name | VARCHAR(100) | 如 business_key |
| property_codes | VARCHAR(500) | 逗号分隔，如 "voucher_no,voucher_date" |
| is_primary | TINYINT | 至少一个为 1 |

#### om_link_type（关系类型定义）

| 列 | 类型 | 必填 | 说明 |
|----|------|------|------|
| link_code | VARCHAR(150) | 是 | `{source}_{verb}_{target}` |
| api_name | VARCHAR(150) | 是 | PascalCase |
| display_name | VARCHAR(200) | 是 | 中文 |
| source_object_id | BIGINT | 是 | |
| target_object_id | BIGINT | 是 | |
| cardinality | VARCHAR(10) | 是 | 1:1/1:N/N:1/N:N |
| link_category | VARCHAR(20) | 是 | FOREIGN_KEY/INFERRED/MANUAL/UNKNOWN |
| is_directional | TINYINT | | 默认 1 |
| reverse_api_name | VARCHAR(150) | | 反向 API 名 |
| reverse_display_name | VARCHAR(200) | | 反向显示名 |
| confidence_score | DECIMAL(3,1) | | |
| source_ref_json | JSON | | |

#### om_link_property（关系属性定义）

| 列 | 类型 | 说明 |
|----|------|------|
| property_code | VARCHAR(100) | |
| api_name | VARCHAR(100) | |
| display_name | VARCHAR(200) | |
| data_type | VARCHAR(50) | |
| is_required | TINYINT | |
| is_unique | TINYINT | |
| enum_values_json | JSON | |

#### om_action_type（动作类型定义）

| 列 | 类型 | 必填 | 说明 |
|----|------|------|------|
| action_code | VARCHAR(150) | 是 | `{verb}_{object_code}`，`^[a-z]+_[a-z0-9_]+$` |
| api_name | VARCHAR(150) | 是 | PascalCase |
| display_name | VARCHAR(200) | 是 | 中文 |
| description | TEXT | | |
| trigger_mode | VARCHAR(20) | 是 | MANUAL/SCHEDULED/EVENT |
| target_object_id | BIGINT | 是 | |
| implementation_type | VARCHAR(20) | 是 | API_CALL/JOB_TRIGGER |

#### om_action_param（动作参数定义）

| 列 | 类型 | 说明 |
|----|------|------|
| param_code | VARCHAR(100) | |
| api_name | VARCHAR(100) | |
| display_name | VARCHAR(200) | |
| data_type | VARCHAR(50) | |
| is_required | TINYINT | |

### 3.2 Mapping 层 — meta 库

#### om_mapping_rule（源字段到属性的映射）

| 列 | 类型 | 必填 | 说明 |
|----|------|------|------|
| source_id | BIGINT | 是 | 数据源 ID |
| object_type_id | BIGINT | 是 | |
| property_id | BIGINT | 是 | |
| source_schema | VARCHAR(100) | 是 | 源库名 |
| source_table | VARCHAR(100) | 是 | 源表名 |
| source_column | VARCHAR(100) | 是 | 源列名 |
| transform_expr | VARCHAR(500) | | 转换表达式，直接映射为 NULL |
| increment_strategy | CHAR(1) | 是 | A/B/C，默认 A |
| biz_key_flag | TINYINT | | 是否组成业务键 |
| mapping_status | VARCHAR(20) | 是 | STABLE/UNSTABLE/PENDING/NEEDS_REVIEW |
| confidence_score | DECIMAL(3,1) | | |

#### om_rule（规则定义）

| 列 | 类型 | 必填 | 说明 |
|----|------|------|------|
| rule_code | VARCHAR(150) | 是 | `rule_{type}_{feature}_{object}` |
| rule_name | VARCHAR(200) | 是 | 中文 |
| rule_type | VARCHAR(20) | 是 | QUALITY/BUSINESS/SECURITY |
| target_scope | VARCHAR(20) | 是 | OBJECT/PROPERTY/LINK/ACTION |
| target_ref_id | BIGINT | 是 | 指向对应表的 id |
| rule_expr | JSON | 是 | 规则表达式 JSON |
| severity | VARCHAR(10) | 是 | LOW/MEDIUM/HIGH |
| is_blocking | TINYINT | | 是否阻断 |

### 3.3 治理层 — meta 库

#### om_model_version

- version_no、version_label、status（INIT→ACTIVE）、is_latest、note
- 每次候选生成创建 INIT 版本，门禁通过后更新为 ACTIVE

#### om_change_log

- change_type（CREATE/UPDATE/DELETE/PUBLISH/ROLLBACK）、entity_type、entity_code、before_json、after_json

### 3.4 Staging 表

与正式表结构一致，表名加 `_staging` 后缀：
- om_object_type_staging
- om_property_staging
- om_object_identifier_staging
- om_link_type_staging
- om_action_type_staging
- om_mapping_rule_staging
- om_rule_staging

所有候选数据先写入 staging（带 run_id + model_version_id），门禁通过后复制到正式表。

---

## 4. 配置格式（YAML）

```yaml
# 必填项
source:
  - type: mysql
    host: 127.0.0.1
    port: 3306
    database: source_db
    username: root
    password: "password"

target:
  type: mysql
  host: 127.0.0.1
  port: 3306
  database: target_db
  username: root
  password: "password"

output_dir: /path/to/artifacts
run_mode: full              # full | incremental
baseline_db: none           # 基线库名称，none 表示不使用

# 可选：域发现
domain_discovery:
  enabled: true
  poll_timeout_sec: 300

# 可选：LLM 客户端
llm_client:
  mode: sdk                 # sdk | handshake
  provider: anthropic       # anthropic | openai
  model: claude-sonnet-4-6
  max_turns: 5
  temperature: 0.3
  api_key_env: ANTHROPIC_API_KEY

# 可选：LLM Review
llm_review:
  enabled: false
  sample_row_limit: 100
  sample_table_limit: 50
  confidence_threshold: 0.6

# 可选：性能配置
performance:
  source_page_mode: id_keyset
  stream_fetch_size: 5000
  write_batch_size: 2000
  reset_target: true

# 可选：多源编排
multi_source:
  - type: mysql
    host: 127.0.0.1
    database: db_a
    username: root
    password: "password"
  - type: mysql
    host: 127.0.0.1
    database: db_b
    username: root
    password: "password"
```

---

## 5. 12 阶段流水线

### 阶段 1：输入解析

**输入**：数据库连接配置、DDL 文件或 Excel/ETS 目录
**动作**：校验输入完整性，生成 run_id，确定 run_mode
**输出**：run_id、run_config

### 阶段 2：对账口径声明

**动作**：声明 comparison_scope、filter_contract、metric_contract
**输出**：`reconciliation_contract.json`（必须含 `anti_clone_guarantee: true`）
**Blocker**：口径不完整 → BLOCKER_SCOPE_CONTRACT_MISSING

### 阶段 3：能力探测

**动作**：探测 MCP SQL、MySQL CLI、catalog_field、目标写权限
**策略选择**：MCP SQL → MySQL CLI → DDL parser → Excel parser
**失败**：无可用策略 → 阻断

### 阶段 4：元数据扫描

**批量采集**（一次查询，不逐表串行）：

1. 获取所有表：`SELECT table_name, table_comment, table_rows, table_type FROM information_schema.tables WHERE table_schema = '{db}'`
2. 获取所有列（优先 catalog_field，回退 information_schema.columns）
3. 获取所有外键：`information_schema.KEY_COLUMN_USAGE`
4. 获取所有约束：`information_schema.TABLE_CONSTRAINTS`
5. 数据采样：对排名前 50 的表执行 `SELECT * LIMIT 100`

**排除表识别**：
- 字典表：`dict_`/`enum_`/`sys_dict` 前缀
- 配置表：`config_`/`setting_`/`param_` 前缀
- 纯中间表：字段数 ≤ 3 且 2 个 FK 字段
- 日志表：`log_`/`audit_`/`history_` 前缀
- 视图：table_type = 'VIEW'

### 阶段 5：建模推导

**依赖顺序**（必须严格遵循）：

```
1. om_model_version（无上游依赖）
2. om_object_type（依赖 model_version_id）
3. om_link_type + om_action_type（依赖 object_type_id）
4. om_property + om_link_property + om_action_param（依赖各自的 type_id）
5. om_object_identifier + om_mapping_rule + om_rule（依赖 object_type_id/property_id）
6. Instance 层：om_object_instance → om_relation_instance → om_action_event
```

详细推导规则见 [第 6 节](#6-推导规则详解)。

### 阶段 6：确定性校验

校验项：
- 枚举值合法性（domain_name、cardinality、link_category、trigger_mode 等）
- 命名 regex（object_code、api_name、property_code 等）
- 主 code 唯一性
- 引用完整性
- JSON 合法性

### 阶段 7：Staging 写入

将候选数据写入 staging 表，使用 run_id + model_version_id 隔离。

### 阶段 8：评分与门禁

SQL 预验证 + 评分计算 + DQ 测试。详见 [第 7 节](#7-评分与门禁系统)。

### 阶段 9：差异归因

将差异映射到统一原因码：SCOPE_MISMATCH / SOURCE_FILTER / MAPPING_GAP / TEMPLATE_GAP / THRESHOLD_EFFECT / DEDUP_EFFECT / SCHEMA_GAP

### 阶段 10：门禁决策

full >= 95 且 blocker=0；incremental >= 92 且 blocker=0。

### 阶段 11：正式库提交

事务写入：staging → target，更新 model_version.status → ACTIVE

### 阶段 12：产物输出

Mermaid 图 + 汇总报告 + 对账清单

---

## 6. 推导规则详解

### 6.1 om_object_type 推导

**输入**：每张非排除业务源表
**输出**：一条 om_object_type 记录

```
规则：
1. object_code = 去掉 t_/tb_/tbl_ 前缀 → 小写 snake_case → 去掉 _info/_base 等无意义后缀
2. api_name = PascalCase(object_code)
3. display_name = table_comment（中文）
4. domain_name = 根据表名/库名/注释推断：finance/investment/hr/cross_domain
5. status = 'DRAFT'
6. primary_key_policy = 'BUSINESS_KEY'（默认）
7. confidence_score = 8.5（rule_score × 0.6 + llm_score × 0.4）
8. source_ref_json = {"schema":"{db}","table":"{table_name}","source_id":N}
```

**反聚合约束**：不要把所有表都映射为 object_type。需检查：
- 有业务 code 字段（非自增主键）→ 独立实体
- 行数 < 100 且含 code+name 模式 → 独立实体（字典/参考数据）
- 参与 N:M 关联 → 独立实体
- 可脱离父表独立查询 → 独立实体
- 纯从属（header/detail 中的 detail）→ 可以合并

### 6.2 om_link_type 推导（4 种方法）

**方法一：外键推导**（link_category = FOREIGN_KEY，置信度 9.5+）
```
源库有物理 FK 约束：
  fund_record.enterprise_id → enterprise.id
  → link_code: fund_record_belongs_to_enterprise
  → cardinality: N:1（FK 列非 UNIQUE）
  → link_category: FOREIGN_KEY
```

**方法二：命名约定推导**（link_category = INFERRED，置信度 7.0）
```
字段名匹配 4 种后缀模式：
  - _uscc（统一社会信用代码）
  - _code（编码）
  - _key（键）
  - _id（ID）
  - parent_ 前缀（自引用）
  - 跨表 _uscc 检测（不同表中同名字段 + 值域重叠）
```

**方法三：中间表推导**（link_category = FOREIGN_KEY）
```
表只有 2-3 个字段且全是 FK：
  t_enterprise_role（enterprise_id + role_id）
  → link_code: enterprise_has_role
  → cardinality: N:N
```

**方法四：值重叠推导**（link_category = INFERRED，置信度 0.70-0.95）
```
两表的同名字段值域交集 > 阈值（如 > 50%）：
  table_a.org_code 与 table_b.org_code 值域高度重叠
  → 推断存在隐式关系
```

**命名规则**：
- link_code: `{source_code}_{verb}_{target_code}`
- 常用动词：belongs_to、has、references、contains、manages

### 6.3 om_action_type 推导

**方法一：状态字段分析**
```
扫描 status/state/phase/stage 类字段的 DISTINCT 值：
  approve_status: pending/approved/rejected
  → submit_xxx（→ pending）
  → approve_xxx（→ approved）
  → reject_xxx（→ rejected）
```

状态值→动作映射查表（52 组中英文）：
| 状态值 | 动作 | 状态值 | 动作 |
|--------|------|--------|------|
| 待审核 | submit_for_review | pending | pending |
| 已驳回/已拒绝 | reject | approved | approve |
| 已完成/已完结 | complete/finish | processing | process |
| 已取消/已作废 | cancel/void | completed | complete |

**方法二：表名/注释推断**
| 表特征 | 动作前缀 | 示例 |
|--------|---------|------|
| 有 approve_status | submit_/approve_/reject_ | approve_fund_record |
| 有 trade/transaction | trade_/execute_ | execute_transaction |
| 有 audit/review | audit_/review_ | audit_application |
| 有 cancel/revoke | cancel_/revoke_ | cancel_contract |

### 6.4 om_property 推导

**输入**：每张业务表的每个字段（排除审计字段）
**输出**：一条 om_property + 一条对应的 om_mapping_rule

**排除字段**：id（自增主键）、create_time/created_at/gmt_create、update_time/updated_at/gmt_modified、create_by/created_by/operator、delete_flag/is_deleted

**推导规则**：
```
property_code = {object_code}_{column_name_snake}（中文列名需翻译为英文）
api_name = camelCase(property_code)
data_type = 映射：
  varchar/char → string
  text/longtext → text
  int/bigint → bigint
  decimal → decimal
  float/double → double
  date → date
  datetime/timestamp → datetime
  tinyint(1) → bool
  json → json
  blob → 跳过
is_required = column.is_nullable == 'NO'
is_unique = column.column_key in ('PRI', 'UNI')
is_searchable = 1（默认）
is_title = 名称/标题类字段
enum_values_json = 从采样数据提取 DISTINCT 值
```

**Data Profiling 增强**（可选）：
通过列级统计修正角色：
- 低唯一性（distinct_rate < 0.01）→ integer_enum_like
- 高唯一性（distinct_rate > 0.95）→ high_uniqueness_identifier
- 高 null 率（> 0.9）→ 降级角色
- 字符串模式匹配：phone/email/id_card/uscc/uuid/md5/date/ip

### 6.5 om_mapping_rule 推导

每条 om_property 对应一条 om_mapping_rule：
```
source_schema = 源库名
source_table = 源表名
source_column = 源列名
transform_expr = NULL（直接映射）
increment_strategy = A（有 update_time）| B（有 biz_date）| C（无增量字段）
biz_key_flag = 组成 object_identifier 的字段 → 1
mapping_status = FOREIGN_KEY 字段 → STABLE | 规则引擎 → STABLE | 其他 → PENDING
```

### 6.6 om_rule 推导

从字段特征生成质量/业务/安全规则：

| 条件 | rule_type | rule_expr 示例 |
|------|-----------|---------------|
| 金额字段（decimal 金额类） | QUALITY | `{"type":"threshold","field":"amount","operator":">","value":1000000}` |
| NOT NULL 字段 | QUALITY | `{"type":"not_null","field":"name"}` |
| 唯一字段 | QUALITY | `{"type":"unique","field":"code"}` |
| 状态字段 | BUSINESS | 状态流转规则 |

**阈值计算**：P95 × 1.2

### 6.7 om_object_identifier 推导

每个 object_type 至少一个 is_primary=1 的标识规则：
```
主键字段（PRI 或含业务编码的 UNIQUE 字段）
→ identifier_name: business_key
→ property_codes: 逗号分隔的 property_code 列表
→ is_primary: 1
```

### 6.8 Instance 层受控生成

#### om_object_instance

从源表真实数据行生成，每行 → 一条 instance：
```
instance_id = hash(object_type_code + 主键值)
display_label = 标题字段的值
properties_json = 所有非主键字段的 JSON
batch_id = run_id
biz_date = 从 biz_date/create_time 等字段提取
```

#### om_relation_instance

从 link_type + FK 字段值生成：
```
source_instance_id = from 侧 instance 的 instance_id
target_instance_id = to 侧 instance 的 instance_id
relation_type_id = link_type.id
properties_json = 中间表其他字段
```

#### om_action_event

从 status/state 字段的不同值生成 STATUS_CHANGE 事件。

---

## 7. 评分与门禁系统

### 7.1 评分模型（100 分制）

| 维度 | 权重 | 满分 | 说明 |
|------|------|------|------|
| structure_coverage | 35% | 35 | 表覆盖率、字段覆盖率、mapping 完整度 |
| semantic_consistency | 30% | 30 | 对象粒度合理性、关系丰富度、语义正确率 |
| event_authenticity | 25% | 25 | 事件来源真实性、上下文完整性 |
| traceability_replay | 10% | 10 | run_id 追溯、差异归因完整性 |

### 7.2 硬 Blocker（任一命中即 FAIL）

| Blocker | 检测方式 |
|---------|---------|
| 主 code 重复（object_code/link_code/action_code/property_code/rule_code） | SQL GROUP BY + HAVING COUNT > 1 |
| api_name 重复 | SQL GROUP BY |
| 悬挂引用（FK 指向不存在的 ID） | SQL LEFT JOIN + IS NULL |
| 非法 JSON（source_ref_json/enum_values_json/rule_expr） | SQL JSON_VALID = 0 |
| 枚举值非法 | SQL NOT IN |
| 源列不存在（mapping_rule.source_column 不在源库） | 与扫描结果对比 |
| 对账口径缺失 | 检查 reconciliation_contract.json |
| 反克隆残留 | 输出 code 中检测到基线库特定模式 |
| 动作对象错配率 > 1% | action_event.object_type_code ≠ action_type.object_type_code |

### 7.3 DQ 底线（必须全部满足）

- dq_not_null_pass_rate >= 99.5%
- dq_unique_pass_rate >= 99.5%
- dq_relationship_pass_rate >= 99.0%
- dq_scope_contract_passed = true

### 7.4 门禁判定

- **full**：Total >= 95 且 blocker_count = 0
- **incremental**：Total >= 92 且 blocker_count = 0
- 不通过：最多重评 2 次，仍不通过则停止并输出修复建议

---

## 8. 安全硬门禁

### 8.1 源库只读保护

实现一个 Guard Cursor 包装器，拦截所有 execute() 调用：

```python
ALLOWED_PREFIXES = ("SELECT", "SHOW", "DESCRIBE", "EXPLAIN", "WITH", "SET SESSION MAX_EXECUTION_TIME")
FORBIDDEN_PATTERNS = [
    r'\bINSERT\b', r'\bUPDATE\b', r'\bDELETE\b', r'\bREPLACE\b',
    r'\bDROP\b', r'\bCREATE\b', r'\bALTER\b', r'\bTRUNCATE\b',
    r'\bGRANT\b', r'\bREVOKE\b', r'\bRENAME\b', r'\bLOCK\b',
    r'\bUNLOCK\b', r'\bFLUSH\b', r'\bLOAD\b', r'\bIMPORT\b',
]
```

只允许 `SET SESSION MAX_EXECUTION_TIME`，拦截其他 SET 命令。

### 8.2 SQL 标识符转义

所有从 LLM 或外部输入传入的表名/列名，必须通过标识符转义函数处理，防止注入。

### 8.3 密码安全

- 禁止命令行明文密码
- API Key 仅从环境变量读取
- 配置文件中的密码通过 pymysql.connect 参数传递

---

## 9. 关键算法

### 9.1 外键发现

```
输入：源库 information_schema
输出：FK 关系列表

1. 显式 FK：查询 information_schema.KEY_COLUMN_USAGE WHERE referenced_table_name IS NOT NULL
2. 命名约定 FK：扫描字段名匹配 _uscc/_code/_key/_id 后缀 + parent_ 前缀
3. 值重叠 FK（可选）：对同名字段计算值域交集
```

### 9.2 Data Profiling（列级统计）

```
输入：源表 + 列列表
输出：每列的统计 profile

对每列计算：
- distinct_count, total_count, null_count
- distinct_rate, null_rate
- 数值列：min, max, avg, p95
- 字符串列：min_len, max_len, avg_len
- 模式检测：匹配 phone/email/id_card/uscc/uuid/md5/date/ip 正则
- Top-N 值：GROUP BY + ORDER BY cnt DESC LIMIT 10
```

**P95 计算策略**：
1. 小表（<100K 行）：精确 SQL
2. 大表：近似 LIMIT 1 OFFSET 策略

### 9.3 状态流转发现

```
输入：包含 status/state/phase/stage 字段的表
输出：状态值 → 动作映射

1. 检测状态列（11 种正则）：
   r'(^|_)status$', r'(^|_)state$', r'(^|_)phase$', r'(^|_)stage$',
   r'(^|_)approve_status$', r'(^|_)audit_status$', r'(^|_)review_status$',
   r'(^|_)process_status$', r'(^|_)flow_status$', r'(^|_)biz_status$',
   r'(^|_)order_status$'

2. 获取 DISTINCT 值（优先 profiling top_values，回退 SQL DISTINCT）

3. 52 组中英文状态值查表映射为动作
```

### 9.4 行业术语检测

```
输入：表名列表 + 字段名列表
输出：行业上下文（金融/医疗/制造/电商/教育/政务/物流/房地产/能源）

9 个行业 × 2 组关键词扫描：
- 表名命中：权重 ×3
- 字段名命中：权重 ×1
- 最高分 → 行业上下文
- 无匹配 → general
```

### 9.5 字段间计算关系发现

```
输入：property_mappings
输出：derivation_hints

检测 3 种模式：
1. amount = price × quantity（列名含 amount/price/quantity）
2. tax = amount × rate（列名含 tax/amount/rate）
3. total = sum of parts（列名含 total + 多个同类列）

中英文双覆盖查表，裸名回退机制
```

### 9.6 Schema Delta 检测（增量模式）

```
输入：当前 schema 指纹 + 历史快照
输出：added/dropped/modified/unchanged 分类

每表指纹 = hash(表名 + 列名排序列表)
全局指纹 = hash(所有表指纹排序)
```

---

## 10. 输出产物清单

每次运行必须产出的文件：

| 产物 | 路径 | 说明 |
|------|------|------|
| 评分报告 | `{output_dir}/score_report.json` | 评分详情 + blocker + 建议 |
| DQ 报告 | `{output_dir}/dq_report.json` | DQ 测试结果 |
| 对账契约 | `{output_dir}/reconciliation_contract.json` | 含 anti_clone_guarantee |
| 对账差异 | `{output_dir}/reconciliation_diff.xlsx` | |
| 差异归因 | `{output_dir}/diff_reason_report.json` | |
| Mermaid 图 | `{output_dir}/mermaid.mmd` | 实体关系图 |
| 汇总报告 | `{output_dir}/summary.md` | Markdown |
| 回放计划 | `{output_dir}/replay_patch_plan.md` | |
| 运行报告 | `{output_dir}/skill_refine_run_report.json` | 含吞吐指标 |
| 域配置 | `{output_dir}/domain_profile.yaml` | 域发现结果（如有） |
| 学习状态 | `{output_dir}/learning_state.json` | 累积运行历史 |
| 改进建议 | `{output_dir}/improvement_suggestions.json` | |

---

## 11. 错误处理与降级

| 错误场景 | 处理方式 |
|---------|---------|
| MCP 不可用 | 降级为 |
| CLI 不可用 | 降级为 DDL 解析 |
| DDL 不可用 | 降级为 Excel/ETS |
| DB 连接失败 | 提示修复连接；可回退文件模式 |
| catalog_field 不存在 | 回退到 information_schema.columns |
| 表/字段注释缺失 | 标记低置信度，LLM 推测 |
| 评分不达标 | 输出扣分项与修复建议，最多重评 2 次 |
| 正式库写入失败 | 保留 staging 数据与报告供手动执行 |
| LLM 超时 | 回退到规则引擎（domain_mappings.yaml） |
| 域发现超时 | 检查 domain_profile.yaml 是否被外部进程写入 |
| 增量模式无快照 | 降级为 full 模式 |
| Checkpoint 损坏 | 删除 checkpoint 重新开始 |

---

## 附录 A：命名规范速查

| 字段 | 格式 | 示例 | 正则 |
|------|------|------|------|
| object_code | snake_case | enterprise, fund_record | `^[a-z][a-z0-9_]*$` |
| api_name (object) | PascalCase | Enterprise, FundRecord | `^[A-Z][a-zA-Z0-9]*$` |
| link_code | `{src}_{verb}_{tgt}` | fund_record_belongs_to_enterprise | `^[a-z][a-z0-9_]*$` |
| action_code | `{verb}_{object}` | submit_fund_record | `^[a-z]+_[a-z0-9_]+$` |
| property_code | `{object}_{field}` | enterprise_name | `^[a-z][a-z0-9_]*$` |
| api_name (property) | camelCase | enterpriseName | `^[a-z][a-zA-Z0-9]*$` |
| rule_code | `rule_{type}_{feature}_{obj}` | rule_threshold_amount_fund_record | `^[a-z][a-z0-9_]*$` |

## 附录 B：权威枚举全集

| 枚举名 | 允许值 |
|--------|--------|
| domain_name | finance, investment, hr, cross_domain |
| status (object) | DRAFT, ACTIVE, INACTIVE |
| model_version_status | INIT, ACTIVE, INVALID |
| link_category | FOREIGN_KEY, INFERRED, MANUAL, UNKNOWN |
| cardinality | 1:1, 1:N, N:1, N:N |
| trigger_mode | MANUAL, SCHEDULED, EVENT |
| implementation_type | API_CALL, JOB_TRIGGER |
| rule_type | QUALITY, BUSINESS, SECURITY |
| target_scope | OBJECT, PROPERTY, LINK, ACTION |
| severity | LOW, MEDIUM, HIGH |
| primary_key_policy | BUSINESS_KEY, AUTO_INCREMENT, UUID |
| increment_strategy | A, B, C |
| mapping_status | STABLE, UNSTABLE, PENDING, NEEDS_REVIEW |
| change_type | CREATE, UPDATE, DELETE, PUBLISH, ROLLBACK, REJECT, INVALIDATE |
| comparison_scope | target_total, target_latest_run, target_time_window |
| diff_reason_code | SCOPE_MISMATCH, SOURCE_FILTER, MAPPING_GAP, TEMPLATE_GAP, THRESHOLD_EFFECT, DEDUP_EFFECT, SCHEMA_GAP |

## 附录 C：Data Profiling 类型常量

```python
NUMERIC_TYPES = {"tinyint", "smallint", "mediumint", "int", "integer", "bigint",
                 "float", "double", "decimal", "real", "numeric"}
STRING_TYPES = {"varchar", "char", "tinytext", "nvarchar", "nchar"}
DATE_TYPES = {"date", "datetime", "timestamp", "time", "year"}
SKIP_TYPES = {"blob", "tinyblob", "mediumblob", "longblob",
              "text", "mediumtext", "longtext", "json", "geometry",
              "binary", "varbinary", "bit", "set"}
```

## 附录 D：字符串模式检测器

| 模式名 | 正则 |
|--------|------|
| phone_number | `^1[3-9]\d{9}$` |
| id_card | `^\d{17}[\dXx]$` |
| email | `^[^@]+@[^@]+\.[^@]+$` |
| url | `^https?://` |
| ip_address | `^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$` |
| uscc | `^[0-9A-HJ-NPQRTUWXY]{2}\d{6}[0-9A-HJ-NPQRTUWXY]{10}$` |
| date_format | `^\d{4}[-/]\d{1,2}[-/]\d{1,2}$` |
| uuid | `^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$` |
| md5 | `^[0-9A-Fa-f]{32}$` |

## 附录 E：审计字段排除列表

以下字段不生成 om_property 和 om_mapping_rule：
- id（自增主键）
- create_time, created_at, gmt_create
- update_time, updated_at, gmt_modified
- create_by, created_by, operator
- delete_flag, is_deleted

## 附录 F：表排除前缀

| 前缀 | 类型 | 处理 |
|------|------|------|
| dict_, enum_, sys_dict | 字典表 | 排除 |
| config_, setting_, param_ | 配置表 | 排除 |
| log_, audit_, history_ | 日志表 | 排除 |
| 中间表（≤3 字段，2 FK） | 关系表 | 提取为 link_type |