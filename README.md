# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## F1 家政派单

三块核心能力：服务人员台账、服务单全量记录、工单号+日期组合检索。

### 通用约定

- **请求号必带**：所有新增/变更/冲销/恢复/派单/改期请求必须携带 `requestNo`（幂等键）。
  双击保存只落一条；重复请求（同 `requestNo`）直接返回首次结果。
- **版本**：变更请求必须携带当前 `version`（乐观锁），落库自动 +1；版本不符拒绝。
- **禁止物理删除**：冲销（`record_status=VOIDED`）与恢复均留 `t_operation_log` 操作记录。
- **主明细同生共死**：服务单主表与明细同一事务落库，任一失败整体回滚。
- **派单规则**：避开人员休假与既有排班（同日同时段；ALLDAY 与任一时段冲突）；
  改期时目标日期冲突自动顺延到最近可用日期（上限 30 天）。

### 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/staff` | 新增人员 |
| PUT | `/api/staff/{id}` | 变更人员（带 version） |
| POST | `/api/staff/{id}/void` `/restore` | 冲销 / 恢复 |
| GET | `/api/staff?keyword=&includeVoided=` | 人员台账列表 |
| POST | `/api/staff/{id}/leaves` | 登记休假 |
| GET | `/api/staff/{id}/leaves` | 休假列表 |
| POST | `/api/staff/leaves/{leaveId}/void` | 冲销休假 |
| POST | `/api/orders` | 新增服务单（主表+明细） |
| PUT | `/api/orders/{id}` | 变更服务单（仅待派单，带 version） |
| POST | `/api/orders/{id}/void` `/restore` | 冲销 / 恢复（明细随主） |
| POST | `/api/orders/{id}/assign` | 派单（校验休假/排班） |
| POST | `/api/orders/{id}/reschedule` | 改期（冲突自动顺延） |
| GET | `/api/orders/search?orderNo=&date=` | 组合检索（至少一个条件） |
| GET | `/api/orders?date=&status=` | 服务单列表 |
| GET | `/api/trace/orders/{orderNo}` | 按工单号检索完整调用时序 |

> 注：本期业务接口在 Shiro 中临时 `anon` 放行，登录模块落地后改回 `authc`。

## F2 全链路追踪

定位线上故障的路径：按工单号拉出调用时序 → 拿当次 traceId 回查日志。

- **TraceId 全链路携带**：入口生成或透传请求头 `X-Trace-Id`（8~64 位字母数字/短横线，非法则重新生成），
  响应头同名回带；日志每行带 `[traceId]`，入口到持久层（`t_trace_record`、`t_operation_log`）一路携带。
- **异步接力**：`traceTaskExecutor` 线程池经 `TaskDecorator` 自动接力上下文（调用轨迹异步落库即走此路径）；
  其他异步边界用 `TraceContext.wrap(Runnable)` 包装即可。
- **按工单号检索调用时序**：`GET /api/trace/orders/{orderNo}` 按发生先后返回该工单的每次调用
  （traceId / uri / 耗时 / 成败 / 失败原因），失败调用同样落轨迹。
- **敏感字段打码**：日志输出经 `%mmsg` 转换器兜底打码（手机号 / 身份证）；操作留痕入库前同样打码。
- 健康检查等探活接口不落调用轨迹，避免淹没主链路。



