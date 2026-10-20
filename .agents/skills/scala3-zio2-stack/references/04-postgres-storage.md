# PostgreSQL、pgvector 与持久化边界

用于 SQL、索引、租户/权限或耐久控制面；沿用仓库已有 JDBC/事务封装。

## 查询与授权

- 用户值全部参数化。表名/排序列等不能绑定的位置只允许代码内固定值或明确 allowlist，不能拼接模型/用户输入。
- 租户、知识空间、Profile、文档范围与权限来自可信服务端上下文，在查询中约束；返回后仍执行现有来源撤回/修订闸门。
- JDBC row 只在适配器内映射。阻塞边界、连接与 ResultSet 的所有权遵循当前仓储约定，不让半关闭对象逃逸。

下面只展示余弦检索的必要谓词形态，不是完整生产查询；active Profile、文档版本与参数类型以实际仓储为准：

```sql
SELECT document_id, chunk_id, chunk_text
FROM agent_knowledge_profile_chunks
WHERE tenant_id = ?
  AND knowledge_space_id = ?
  AND profile_id = ?
  AND permissions <@ ?::text[]
ORDER BY embedding <=> ?::vector
LIMIT ?
```

向量维度在固定 Profile/模型身份边界校验；维度相同不代表 embedding 模型可混用。
余弦操作符 `<=>` 必须匹配实际 operator class；SQL 写了 ORDER BY/LIMIT 不保证执行 HNSW，权限谓词也不保证 ANN 召回数量，需执行计划与受限语料测试验证。不为“强制用索引”移除安全过滤。

## 事务与耐久性

本地业务变更及相应 outbox/inbox/checkpoint 按既有事务协议原子保存。不要把远端 HTTP/模型调用放进长期持锁事务。
worker lease/CAS/fencing 在最终写入时验证，不能仅在读取时检查。补偿/重试须依据真实副作用与幂等约定。
已发布 migration 不原地重写；schema/version 变更按仓库兼容与回滚规则处理。

集成测试只用仓库定义的临时数据库入口。生产查询、重建、删除和发布各自需要已有授权；skill 和测试开关不授予操作权限。

索引与过滤行为见 [pgvector 官方说明](https://github.com/pgvector/pgvector#filtering)；不要直接复制其他版本的调参值。
