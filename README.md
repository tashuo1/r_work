# 本体数据平台一期

大模型驱动的本体论大数据平台一期工程，当前以 `ontology-lite/ontology-server` 为主线，采用 Spring Boot 分层单体实现产品闭环演示。

## 当前能力

- 数据源管理：连接信息维护、JDBC 连接测试、启停、删除和巡检。
- 本体建模：元数据扫描、候选建模、语义图谱、版本发布和回滚。
- 数据标准：AI 生成候选、命名词根、标准字段、审核和导出。
- T+1 同步：调度查看、手动触发、质量结果。
- 语义问数：自然语言问数、SQL 安全闸、图表和 CSV。
- OpenAPI：本地元数据、属性、实例查询。
- 验收清单：`/api/v1/acceptance`。

## 技术栈

- Java 17
- Spring Boot 3
- MyBatis
- Maven
- MySQL/PolarDB 或本地 H2 兜底
- 静态 HTML/CSS/JavaScript 产品界面

## 快速启动

```bash
git clone https://github.com/tashuo1/r_work.git
cd r_work
cp ontology-lite/runtime-config/ontology-platform.env.example ontology-lite/runtime-config/ontology-platform.env
./start-ontology-platform.sh start
```

访问：

```text
http://127.0.0.1:9000/
```

停止服务：

```bash
./start-ontology-platform.sh stop
```

## 测试

```bash
cd ontology-lite/ontology-server
mvn test
```

## 目录

```text
docs/superpowers/plans/        产品、架构、技术规范、WBS 和决策文档
docs/superpowers/specs/        Agent Team 与页面改造规格
ontology-lite/ontology-server/ 当前 Spring Boot 分层单体服务
start-ontology-platform.sh     一键构建、启动、重启、停止脚本
```

## 配置说明

运行配置从 `ontology-lite/runtime-config/ontology-platform.env` 读取。仓库只提交 `ontology-platform.env.example`，真实连接串、账号、密码和本地运行数据不会提交。

默认示例配置使用本地 H2 文件库，方便新开发者直接启动。连接 MySQL/PolarDB 时复制示例文件后修改 `ONTOLOGY_DB_URL`、`ONTOLOGY_DB_USERNAME`、`ONTOLOGY_DB_PASSWORD`。

## 开发约束

- 当前只维护 `ontology-lite/ontology-server`。
- 不恢复旧微服务工程，不引入不必要中间件。
- SQL 安全闸：仅允许 SELECT、自动 LIMIT、timeout、EXPLAIN 预检、禁止跨源库 join。
- 词典规则：词根为单一中文业务词拼音；标准字段为已登记规范词根的下划线组合。
- 本地运行目录、日志、PID、数据库文件和真实 `.env` 不进入版本库。
