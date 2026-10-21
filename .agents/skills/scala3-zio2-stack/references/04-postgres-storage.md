# 04. PostgreSQL、pgvector 与持久化边界

用于数据库访问、pgvector 向量语义检索、租户隔离、CAS 乐观锁与持久化事务。

## 一、 严格参数化查询与零 SQL 注入 (Parameterized SQL)

- **绝对铁律**：所有来自外部、用户、大模型输出或包含动态参数的查询值，**必须 100% 使用 PreparedStatement 占位符 `?`**。
- **插值限制**：字符串插值（`s"..."`）**仅被允许**用于编译期固定的 SQL 常量或经由严格白名单检验的静态表名/列名。
- **防拼接示例**：
  ```scala
  // 严禁：字符串直接拼接用户输入
  val badSql = s"SELECT * FROM users WHERE name = '$username'" 

  // 正确：参数化绑定
  val query = "SELECT id, email, created_at FROM users WHERE name = ?"
  // 通过 PreparedStatement 绑定具体参数
  ```

## 二、 pgvector 向量检索与租户/权限预过滤

在执行基于余弦距离操作符（`<=>`）的向量语义检索时，必须同步强制施加租户与数据范围过滤：

```sql
SELECT document_id, chunk_id, chunk_text, (1 - (embedding <=> ?::vector)) AS score
FROM agent_knowledge_profile_chunks
WHERE tenant_id = ?
  AND knowledge_space_id = ?
  AND profile_id = ?
  AND permissions <@ ?::text[]
ORDER BY embedding <=> ?::vector
LIMIT ?;
```

1. **索引与操作符类匹配（Operator Class）**：
   余弦距离操作符 `<=>` 必须与索引的操作符类严格对齐。创建 HNSW 索引时必须指定 `vector_cosine_ops`：
   ```sql
   CREATE INDEX idx_chunks_embedding_hnsw 
   ON agent_knowledge_profile_chunks USING hnsw (embedding vector_cosine_ops);
   ```
   严禁使用默认的 L2 距离类（`vector_l2_ops`）去加速余弦查询，这会导致索引失效或排序错误。
2. **向量维度校验**：在调用数据库之前，必须在应用层严格校验待检索向量的维度（如 1024 或 1536），防止因 Embedding 模型混用或配置漂移引发底层数据库异常。
3. **严禁牺牲安全迎合索引**：某些情况下 HNSW 索引可能在多重过滤谓词下发生索引退化，**严禁为了“强制命中索引”而擅自移除 `tenant_id` 或权限过滤条件**。
4. **撤回与修订校验**：检索结果返回至内存后，仍需执行应用层的最新版本与撤回状态二度校验，防止读取已废弃但未完成物理清理的脏数据。

## 三、 并发控制：CAS 乐观锁与 Fencing Token

1. **版本单调递增（CAS）**：
   对状态发生流转的实体（如 Run、Task、Document），更新操作必须显式检验预期版本号：
   ```sql
   UPDATE agent_runs 
   SET status = ?, state_payload = ?, version = version + 1, updated_at = NOW()
   WHERE id = ? AND version = ?;
   ```
   若更新受影响行数为 0，说明存在并发冲突，必须作为 `OptimisticLockConflict` 错误妥善抛出并实施回滚或重试，绝不覆盖他人写入。
2. **Fencing Token 防僵尸 Worker**：
   后台分布式 Worker 在提交执行结果时，必须携带当前租约的 Fencing Token；如果检测到租约已过期或已被新 Worker 抢占，旧 Worker 的提交必须立即 Fail-Closed，杜绝数据错乱。

## 四、 事务边界与 Outbox / Inbox 模式

- **局部事务原子性**：本地业务状态的变更与随之产生的审计日志、Outbox 领域事件，必须在同一个本地数据库事务中提交。
- **严禁长事务包裹远程调用**：**严禁将耗时极长或不可控的网络 HTTP 请求、大模型推理调用包裹在持有数据库锁的事务块内**。远程调用完成后，通过独立的 CAS 事务写入结果。
