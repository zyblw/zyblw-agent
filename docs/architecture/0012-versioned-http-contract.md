# ADR-0012：独立、版本化的 HTTP 公共契约

> 历史决策：公共 wire contract 与内部状态隔离的原则继续有效；“单独发布 artifact”部分已由
> [ADR-0014](0014-consolidate-public-modules.md) 修订为 `zyblw-agent-zio-http` 内部独立 package。

状态：Accepted
日期：2026-07-15

## 背景

早期 HTTP Adapter 直接序列化 `AgentState` 与 `PersistedAgentEvent`。这会把恢复游标、工具参数、内部消息表示和未来数据库
演进变成对外兼容负担，也无法机械生成可信 OpenAPI。项目尚未正式发布，没有保留无版本草案路径的价值。

## 决策

1. 建立不依赖 Runtime 的 `agent-http-contract` 模块。
2. 首次公共基线使用 `/api/v1`，删除无版本别名。
3. DTO 同时提供 zio-json codec 与 ZIO Schema；Endpoint 是路径与 OpenAPI 的单一事实源。
4. `agent-http` 通过 `AgentHttpProjection` 显式投影授权后的内部对象。
5. SSE 与 JSON 事件都输出 `RunEventView`，不输出 Event Store payload。
6. 公开事件名显式穷尽映射，不依赖 Scala 类名。
7. 身份方案继续由宿主决定，公共 OpenAPI 不伪造固定认证机制。
8. Memory 治理先使用 v1 路径但保留 Beta 标签，完成 Schema/Endpoint 门禁后再纳入稳定契约。

## 结果

收益：客户端可轻量依赖协议模块；内部 Runtime 与持久化可独立演进；OpenAPI 可机械生成；敏感数据泄漏面显著缩小；不兼容
变更必须显式创建 v2。

代价：实现层需要维护投影；Endpoint 与手写 Request handler 仍需契约测试防漂移。之所以暂不全部使用
`Endpoint.implement`，是因为宿主身份解析器需要访问原始 Request，框架不应为了代码形式统一而削弱认证边界。

## 后续门禁

- 发布 OpenAPI 基线并做结构化兼容 diff；
- 对生成客户端运行旧版兼容 smoke；
- 增加 JVM 二进制兼容检查；
- 把 Memory Beta DTO 迁入 contract；
- 若引入 v2，提供明确迁移期和弃用遥测，不在 v1 内偷偷重定义语义。
