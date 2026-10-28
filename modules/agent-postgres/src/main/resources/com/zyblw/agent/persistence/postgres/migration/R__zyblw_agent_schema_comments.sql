-- zyblw-agent PostgreSQL 核心控制面中文数据字典（可重复迁移）。
--
-- 本文件为幂等 repeatable migration，集中维护 zyblw_agent_core 全部 32 张权威表及所有字段的中文说明。
-- 采用按表精确分派（WHEN column_record.table_name = '...'）而非全局列名兜底，彻底消除同名字段在不同表中的语义串味；
-- 任何未显式映射的表或字段都会触发 RAISE EXCEPTION 使迁移失败，杜绝新增列遗漏注释或退化为模板占位。

DO $comments$
DECLARE
  target_schema TEXT := current_schema();
  table_names TEXT[] := ARRAY[
    'agent_runs',
    'agent_events',
    'tool_executions',
    'agent_suspensions',
    'agent_memories',
    'agent_memory_audit',
    'agent_run_commands',
    'agent_run_dispatch',
    'agent_business_operations',
    'agent_outbox_events',
    'agent_inbox_messages',
    'agent_compensations',
    'agent_embedding_cache',
    'agent_embedding_quota_windows',
    'agent_embedding_quota_reservations',
    'agent_eval_snapshots',
    'agent_workflow_checkpoints',
    'agent_workflow_node_executions',
    'agent_workflow_waits',
    'agent_workflow_signals',
    'agent_runtime_overrides',
    'agent_ingestion_jobs',
    'model_call_executions',
    'harness_goals',
    'harness_plans',
    'harness_skills',
    'harness_interactions',
    'harness_goal_budgets',
    'harness_budget_reservations',
    'agent_artifacts',
    'agent_artifact_versions',
    'agent_artifact_audit'
  ];
  table_name_item TEXT;
  table_record RECORD;
  column_record RECORD;
  comment_text TEXT;
BEGIN
  EXECUTE format(
    'COMMENT ON SCHEMA %I IS %L',
    target_schema,
    'Agent 核心控制面事实 Schema：承载运行状态机、事件溯源、工具与模型调用执行账本、调度租约、长期记忆、工作流、评测快照与治理审计。'
  );

  FOREACH table_name_item IN ARRAY table_names LOOP
    IF to_regclass(format('%I.%I', target_schema, table_name_item)) IS NULL THEN
      RAISE EXCEPTION 'zyblw-agent table %.% does not exist when applying Chinese comments',
        target_schema, table_name_item;
    END IF;
  END LOOP;

  FOR table_record IN
    SELECT c.relname AS table_name
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = target_schema
      AND c.relkind = 'r'
      AND c.relname NOT LIKE 'flyway_%'
    ORDER BY c.relname
  LOOP
    comment_text := CASE table_record.table_name
      WHEN 'agent_runs' THEN 'Agent 运行（Run）权威主表：保存状态机、乐观锁版本、完整 RunState 快照、异步启动幂等指纹及管理台检索派生列。'
      WHEN 'agent_events' THEN 'Agent 运行事件流表：按单次运行内严格递增序号（sequence）持久化不可变生命周期事件轨迹，用于时间线回放与可观测性审计。'
      WHEN 'tool_executions' THEN '工具调用执行账本表：记录每个工具批次与单次调用的 Prepared/Running/Succeeded/Failed/Unknown 状态、重试次数与幂等键，支撑崩溃恢复与副作用去重。'
      WHEN 'agent_suspensions' THEN '运行挂起与到期租约索引表：仅保存挂起分类（审批/人工输入/外部信号/定时器）、截止时间、到期策略与排他认领租约，不冗余挂起敏感正文。'
      WHEN 'agent_memories' THEN '长期记忆存储表：按会话（session）、用户（user）或租户（tenant）作用域隔离持久化结构化记忆，支持全文检索（tsvector）、有效期与保留版本号的软删除墓碑（tombstone）。'
      WHEN 'agent_memory_audit' THEN '记忆治理不可变审计表：记录记忆读取、检索、修正、删除、清理与导出的低敏合规证据，严禁保存记忆正文、明文键名或原始检索词。'
      WHEN 'agent_run_commands' THEN '运行控制命令队列表：持久化 Start、Recover、ResumeApproval、Cancel、Retry 控制指令，支持优先级调度、幂等去重、重试退避与死信隔离。'
      WHEN 'agent_run_dispatch' THEN '运行单活调度租约表：按 run_id 维护 Idle/Queued/Leased 状态与 fencing 代数（generation），确保同一 Run 在任意时刻最多由一个命令工作协程排他推进。'
      WHEN 'agent_business_operations' THEN '工具侧业务写操作幂等账本表：在工具执行事务内与业务状态变更及 Outbox 事件同事务提交，防止重试造成重复业务写入。'
      WHEN 'agent_outbox_events' THEN '事务性发件箱（Transactional Outbox）事件表：与业务操作同事务落库，由独立后台 Worker 租约认领并至少一次投递至下游目标通道。'
      WHEN 'agent_inbox_messages' THEN '下游消费者收件箱（Transactional Inbox）幂等表：按 consumer_name 与 message_id 唯一去重，与下游消费者业务副作用在同一事务内完成状态推进。'
      WHEN 'agent_compensations' THEN '显式 Saga 业务补偿计划表：在正向工具操作同事务内预注册（Registered），仅当上游显式激活转为 Pending 后才由补偿 Worker 认领执行。'
      WHEN 'agent_embedding_cache' THEN '向量化（Embedding）精确缓存表：按租户、用途、供应商、模型、维度、键版本与内容 SHA-256 哈希精确复用浮点向量，不保存原始明文。'
      WHEN 'agent_embedding_quota_windows' THEN '向量化租户配额时间窗口表：按租户、窗口毫秒跨度与窗口起点聚合请求数、文本条数与字符数，通过行级锁串行化配额校验与扣减。'
      WHEN 'agent_embedding_quota_reservations' THEN '向量化配额请求幂等预留账本表：按租户与 request_id 唯一记录单次向量化消耗，与配额窗口累加同事务提交，防止重试重复扣减配额。'
      WHEN 'agent_eval_snapshots' THEN '离线评测不可变快照表：持久化 Agent、可靠性、Harness 对比、RAG 与上下文压缩评测套件的低敏汇总指标与完整性校验哈希。'
      WHEN 'agent_workflow_checkpoints' THEN '声明式工作流（Workflow）耐久恢复检查点表：按 run_id 保存工作流定义版本、当前游标位置与完整状态快照，仅允许同身份内按 step 单调推进。'
      WHEN 'agent_workflow_node_executions' THEN '声明式工作流节点执行台账表：管理单步节点 Running/Prepared/Committed 三阶段状态、分布式 fencing 租约与待提交节点输出（Prepared outcome）。'
      WHEN 'agent_workflow_waits' THEN '声明式工作流耐久等待（Timer/Signal）表：与节点执行及检查点同事务注册，协调信号到达或超时触发后的唯一唤醒认领边界。'
      WHEN 'agent_workflow_signals' THEN '声明式工作流外部信号去重回执表：完整记录投递至指定等待节点的每一次信号尝试及其采纳结果（Accepted/Late/AlreadyResolved）。'
      WHEN 'agent_runtime_overrides' THEN '运行时动态治理覆盖配置表：按严格递增版本号持久化经管理员鉴权确认的运行时覆盖策略、操作人及变更原因。'
      WHEN 'agent_ingestion_jobs' THEN '知识入库（Ingestion）异步任务主表：跟踪文档从排队、加载、切分、向量化、暂存到激活切换的全生命周期状态、进度百分比与失败码。'
      WHEN 'model_call_executions' THEN '大模型调用执行台账表：按 run_id 与 request_id 记录模型推理请求状态、请求指纹、消息/工具计数及受捕获策略（CapturePolicy）约束的执行记录。'
      WHEN 'harness_goals' THEN '长程自主代理（Harness）目标表：管理会话线程内的顶层任务目标描述、生命周期状态、关联运行 ID、修订代数与产物引用列表。'
      WHEN 'harness_plans' THEN '长程自主代理（Harness）执行计划表：归属特定目标（goal_id），记录结构化待办事项列表（todos_json）、计划摘要、关联产物与修订代数。'
      WHEN 'harness_skills' THEN '长程自主代理（Harness）技能注册表：按 skill_id 与 skill_version 版本化管理技能指令正文、来源标识、信任等级（Untrusted/Reviewed/Trusted）与内容指纹。'
      WHEN 'harness_interactions' THEN '长程自主代理（Harness）人机交互指令表：按目标内严格递增序号记录运行过程中的方向纠偏（Steer）、追问跟进（FollowUp）与用户消息。'
      WHEN 'harness_goal_budgets' THEN '长程自主代理（Harness）目标级多维预算池表：统一管控单个目标下的运行次数、模型调用数、工具调用数、输入/输出/总 Token 及估算成本的上限、预留量与已结算消耗量。'
      WHEN 'harness_budget_reservations' THEN '长程自主代理（Harness）单次运行预算预留与结算表：按 run_id 记录从目标预算池切出的单次运行限额、预留状态及终态实际资源消耗。'
      WHEN 'agent_artifacts' THEN 'Agent 二进制/结构化产物（Artifact）逻辑主表：按作用域（session/user/run）、作用域键与产物名称维护产物身份及最新版本号。'
      WHEN 'agent_artifact_versions' THEN 'Agent 产物不可变版本内容表：按版本号存储产物的媒体类型、字节大小、SHA-256 校验和、扩展元数据及二进制内容（BYTEA）。'
      WHEN 'agent_artifact_audit' THEN 'Agent 产物治理不可变审计表：记录产物保存（save）、读取（read）、删除（delete）与清理（purge）动作及名称哈希，不暴露原始产物名或正文。'
      ELSE NULL
    END;

    IF comment_text IS NULL THEN
      RAISE EXCEPTION 'zyblw-agent table %.% is missing a Chinese COMMENT mapping',
        target_schema, table_record.table_name;
    END IF;

    EXECUTE format('COMMENT ON TABLE %I.%I IS %L', target_schema, table_record.table_name, comment_text);
  END LOOP;

  FOR column_record IN
    SELECT c.relname AS table_name, a.attname AS column_name
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = target_schema
      AND c.relkind = 'r'
      AND c.relname NOT LIKE 'flyway_%'
      AND a.attnum > 0
      AND NOT a.attisdropped
    ORDER BY c.relname, a.attnum
  LOOP
    comment_text := CASE
      -- =========================================================================
      -- 1. agent_runs：Agent 运行权威主表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_runs' THEN CASE column_record.column_name
        WHEN 'run_id' THEN 'Agent 运行（Run）全局唯一主键（UUID）。'
        WHEN 'session_id' THEN '所属多轮对话或工作会话标识（UUID），用于串联同一会话上下文下的多次运行。'
        WHEN 'agent_id' THEN '执行本次运行的逻辑 Agent 定义标识（非空文本）。'
        WHEN 'status' THEN 'Run 权威状态机当前状态（Created、Running、WaitingForApproval、Suspended、Completed、Failed、Cancelled、TimedOut、BudgetExceeded）。'
        WHEN 'version' THEN 'Run 状态乐观并发控制版本号（从 0 开始单调递增，每次状态转换写库时校验并加 1）。'
        WHEN 'schema_version' THEN 'state_json 序列化契约版本号（当前固定为 1，数据库层强制精确匹配以拒绝历史旧结构降级）。'
        WHEN 'state_json' THEN '完整 RunState 状态机快照（JSONB），承载消息历史、上下文变量、挂起详情与运行结果等权威事实。'
        WHEN 'cancel_requested' THEN '协作式取消请求标记：为 true 时表示外部已请求取消，内核在下一个安全检查点收敛为 Cancelled。'
        WHEN 'start_scope_hash' THEN '异步 StartRun 幂等作用域 SHA-256 哈希（64 位十六进制），由租户/用户/会话边界派生，不保存明文身份。'
        WHEN 'start_idempotency_key' THEN '异步 StartRun 调用方提供的幂等键（1–200 字符），与 start_scope_hash 联合唯一防重。'
        WHEN 'start_request_hash' THEN '异步 StartRun 请求初始载荷的 SHA-256 哈希（64 位十六进制），用于检测同幂等键下的参数冲突。'
        WHEN 'tenant_id' THEN '从 state_json.runContext.tenantId 自动提取的生成列（STORED），专供多租户管理台过滤与索引加速。'
        WHEN 'user_id' THEN '从 state_json.runContext.userId 自动提取的生成列（STORED），标识发起本次运行的主体用户。'
        WHEN 'awaiting_approval' THEN '由 status = ''WaitingForApproval'' 自动派生的布尔生成列（STORED），供管理台待审批队列部分索引使用。'
        WHEN 'awaiting_signal' THEN '由 status = ''Suspended'' 自动派生的布尔生成列（STORED），标识等待外部信号、人工输入或定时器的非审批挂起。'
        WHEN 'created_at' THEN 'Run 创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN 'Run 最后一次状态推进或持久化更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 2. agent_events：Agent 运行事件流表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_events' THEN CASE column_record.column_name
        WHEN 'event_id' THEN '运行事件全局唯一主键（UUID）。'
        WHEN 'run_id' THEN '所属 Agent 运行标识（外键关联 agent_runs.run_id，级联删除）。'
        WHEN 'sequence' THEN '同一次运行内从 0 开始严格递增的事件序号，与 run_id 组成唯一约束确保事件流全序。'
        WHEN 'event_type' THEN '运行时领域事件类型名称（如 RunCreated、RunStarted、StepStarted、ToolCalled、Suspended、RunCompleted、RunFailed、RunCancelled 等）。'
        WHEN 'payload' THEN '与 event_type 对应的结构化事件载荷（JSONB）。'
        WHEN 'created_at' THEN '事件写入持久化存储的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 3. tool_executions：工具调用执行账本表
      -- =========================================================================
      WHEN column_record.table_name = 'tool_executions' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '所属 Agent 运行标识（外键关联 agent_runs.run_id，与 call_id 组成复合主键）。'
        WHEN 'batch_id' THEN '同一次模型响应触发的并行/顺序工具调用批次标识。'
        WHEN 'ordinal' THEN '工具调用在当前批次（batch_id）内的稳定执行序号（从 0 开始）。'
        WHEN 'call_id' THEN '单次工具调用在 Run 内的唯一标识（对应模型返回的 tool_call_id）。'
        WHEN 'tool_name' THEN '被调用的工具注册名称。'
        WHEN 'idempotency_key' THEN '可选的工具执行幂等键（在同一次 run_id 内唯一，防止恢复重试时重复触发外部副作用）。'
        WHEN 'status' THEN '工具调用执行状态机：Prepared（已准备）、Running（执行中）、Succeeded（成功）、Failed（失败）、Unknown（崩溃后结果未知待核实）。'
        WHEN 'attempt' THEN '当前工具调用已尝试执行的次数（从 0 开始计数）。'
        WHEN 'record_json' THEN '工具调用完整执行记录快照（JSONB，包含入参摘要、结构化输出或错误详情）。'
        WHEN 'updated_at' THEN '工具执行记录最后更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 4. agent_suspensions：运行挂起与到期租约索引表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_suspensions' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '处于挂起状态的 Agent 运行主键（外键关联 agent_runs.run_id，一运行最多一条挂起索引记录）。'
        WHEN 'kind' THEN '挂起等待分类：approval（人工审批）、human-input（补充人工输入）、external-signal（外部异步信号）、timer（耐久定时器）。'
        WHEN 'deadline' THEN '挂起等待截止时间（TIMESTAMPTZ）；为 NULL 表示无限期等待，不进入到期扫描部分索引。'
        WHEN 'expiry_outcome' THEN '到达 deadline 后的自动决议动作：FailRun（使运行失败）或 ResumeWithDefault（按默认回退值恢复运行）。'
        WHEN 'status' THEN '挂起生命周期状态：Pending（等待中）、Expired（已到期待后台 Worker 处置）、Resolved（已恢复或已终止决议）。'
        WHEN 'created_at' THEN '挂起记录创建时间（TIMESTAMPTZ）。'
        WHEN 'resolved_at' THEN '挂起转入 Expired 或 Resolved 状态的时间戳（Pending 状态下必须为 NULL）。'
        WHEN 'expiry_generation' THEN '到期处置租约 fencing 代数（每次被 SuspensionExpiryWorker 认领时单调递增）。'
        WHEN 'expiry_owner' THEN '当前持有到期处置租约的后台 Worker 节点标识。'
        WHEN 'expiry_token' THEN '当前到期处置租约的排他校验令牌（UUID）。'
        WHEN 'expiry_claimed_at' THEN '当前 Worker 认领该到期挂起项的时间戳（TIMESTAMPTZ）。'
        WHEN 'expiry_lease_expires_at' THEN '当前到期处置租约的过期时间（超过此时间未续租则允许其他 Worker 抢占恢复）。'
        WHEN 'expiry_heartbeat_at' THEN '当前到期处置租约最后一次心跳续期时间（需满足 claimed_at <= heartbeat_at < lease_expires_at）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 5. agent_memories：长期记忆存储表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_memories' THEN CASE column_record.column_name
        WHEN 'scope_kind' THEN '记忆隔离作用域类型：session（单会话记忆）、user（跨会话用户级记忆）、tenant（租户共享记忆）。'
        WHEN 'scope_key' THEN '规范化作用域分区键（与 scope_kind、memory_key 组成复合主键）。'
        WHEN 'tenant_id' THEN '所属租户标识（当 scope_kind 为 user 或 tenant 时非空，session 作用域下为 NULL）。'
        WHEN 'user_id' THEN '所属用户标识（仅当 scope_kind 为 user 时非空，用于用户级记忆治理与合规导出/擦除）。'
        WHEN 'session_id' THEN '所属会话 UUID（仅当 scope_kind 为 session 时非空）。'
        WHEN 'memory_key' THEN '作用域内唯一标识该条记忆的业务键名（1–200 字符）。'
        WHEN 'value_json' THEN '结构化记忆正文内容（JSONB）；当 status = ''deleted'' 转为墓碑行时强制置空以彻底擦除数据。'
        WHEN 'search_text' THEN '供全文检索使用的纯文本摘要；当 status = ''deleted'' 时强制置空。'
        WHEN 'search_vector' THEN '基于 search_text 自动生成的 simple 分词全文检索向量（TSVECTOR STORED 生成列）。'
        WHEN 'memory_kind' THEN '认知记忆分类：preference（用户偏好）、semantic（语义事实）、episodic（情景经历）、procedural（流程经验）。'
        WHEN 'importance' THEN '记忆重要度评分（0.0 到 1.0 之间的双精度浮点数，用于检索召回排序与淘汰裁剪）。'
        WHEN 'confidence' THEN '记忆置信度评分（0.0 到 1.0 之间的双精度浮点数）。'
        WHEN 'sensitivity' THEN '数据敏感度分级：public（公开）、personal（个人一般信息）、sensitive（高敏受控信息）。'
        WHEN 'evidence' THEN '记忆事实来源：user_stated（用户明确陈述）、tool_observed（工具观测结果）、imported（外部导入）、model_inferred（模型归纳推断）。'
        WHEN 'extractor_version' THEN '产出该条记忆的提取器或策略组件版本标识（1–100 字符）。'
        WHEN 'source_run_id' THEN '产出或最后更新该条记忆的源 Agent 运行 ID（可选 UUID）。'
        WHEN 'version' THEN '记忆条目乐观锁版本号（从 1 开始严格递增，软删除墓碑仍保留递增版本以防旧写复活）。'
        WHEN 'status' THEN '记忆条目状态：active（有效可检索）或 deleted（已擦除正文的软删除墓碑）。'
        WHEN 'created_at' THEN '记忆首次创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '记忆最后更新或软删除时间（TIMESTAMPTZ）。'
        WHEN 'expires_at' THEN '记忆自动过期时间（可选 TIMESTAMPTZ，到期后由后台治理任务清理）。'
        WHEN 'deleted_at' THEN '记忆被软删除擦除的时间戳（active 状态下为 NULL，deleted 状态下非空）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 6. agent_memory_audit：记忆治理不可变审计表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_memory_audit' THEN CASE column_record.column_name
        WHEN 'audit_id' THEN '记忆治理审计记录全局唯一主键（UUID）。'
        WHEN 'action' THEN '记忆治理动作类型：read（单条读取）、list（列表枚举）、search（检索）、correct（人工/系统修正）、delete（单条删除）、delete_scope（整域清空）、retention_purge（到期保留清理）、export（合规导出）。'
        WHEN 'actor_kind' THEN '触发治理动作的操作主体类别：authenticated（已认证租户/用户）或 system（后台系统守护任务）。'
        WHEN 'actor_tenant_id' THEN '已认证操作主体所属租户标识（system 主体时为 NULL）。'
        WHEN 'actor_user_id' THEN '已认证操作主体的用户标识（system 主体时为 NULL）。'
        WHEN 'actor_system_name' THEN '后台系统守护任务名称（仅当 actor_kind = ''system'' 时非空）。'
        WHEN 'target_scope_kind' THEN '被操作的目标记忆作用域类型：session、user 或 tenant。'
        WHEN 'target_scope_key' THEN '被操作的目标记忆作用域键值。'
        WHEN 'target_tenant_id' THEN '被操作目标记忆所属租户标识（target_scope_kind 为 user/tenant 时非空）。'
        WHEN 'target_user_id' THEN '被操作目标记忆所属用户标识（target_scope_kind 为 user 时非空）。'
        WHEN 'target_session_id' THEN '被操作目标记忆所属会话 UUID（target_scope_kind 为 session 时非空）。'
        WHEN 'memory_key_hash' THEN '目标 memory_key 的小写十六进制 SHA-256 哈希（64 字符，整域操作或列表检索时可为 NULL，严禁存明文键）。'
        WHEN 'expected_version' THEN '调用方在执行修正或删除时声明的期望乐观锁版本号（可选）。'
        WHEN 'resulting_version' THEN '治理写操作完成后的新版本号（只读或批量操作时为 NULL）。'
        WHEN 'affected_count' THEN '本次治理动作实际命中或影响的记忆条目数量（>= 0）。'
        WHEN 'reason_code' THEN '治理操作原因码（1–80 字符，如用户主动删除、隐私合规请求、TTL 到期等）。'
        WHEN 'occurred_at' THEN '治理动作实际发生的业务时间戳（TIMESTAMPTZ）。'
        WHEN 'created_at' THEN '审计记录写入数据库的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 7. agent_run_commands：运行控制命令队列表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_run_commands' THEN CASE column_record.column_name
        WHEN 'command_id' THEN '运行控制命令全局唯一主键（UUID）。'
        WHEN 'run_id' THEN '目标 Agent 运行标识（外键关联 agent_runs.run_id，级联删除）。'
        WHEN 'command_type' THEN '控制命令类型：Start（启动运行）、Recover（故障恢复）、ResumeApproval（提交审批决议恢复）、Cancel（取消运行）、Retry（重试运行）。'
        WHEN 'payload' THEN '控制命令执行所需的序列化参数载荷（JSONB）。'
        WHEN 'idempotency_key' THEN '同一次 run_id 内唯一标识该命令的幂等键，防止重复入队相同控制指令。'
        WHEN 'status' THEN '控制命令队列状态：Queued（排队待认领）、Leased（已被 Worker 租约认领执行中）、Completed（执行完成）、DeadLetter（超过重试上限进入死信）、Superseded（被更高优先级或终态命令取代）。'
        WHEN 'priority' THEN '命令调度优先级（整数，数值越大越优先被 Worker 认领，例如 Cancel 高于普通执行命令）。'
        WHEN 'available_at' THEN '命令最早允许被 Worker 认领执行的时间戳（用于指数退避重试延迟调度）。'
        WHEN 'attempt' THEN '当前命令已尝试执行的次数（从 0 开始计数）。'
        WHEN 'manual_retry_count' THEN '管理员从死信队列（DeadLetter）手动触发重放的累计次数。'
        WHEN 'last_failure' THEN '最近一次命令执行失败的脱敏错误摘要（未失败时为 NULL）。'
        WHEN 'created_at' THEN '命令入队创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '命令状态最后更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 8. agent_run_dispatch：运行单活调度租约表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_run_dispatch' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '受单活调度保护的 Agent 运行主键（外键关联 agent_runs.run_id，级联删除）。'
        WHEN 'status' THEN 'Run 调度器槽位状态：Idle（空闲无待办命令）、Queued（有待办命令等待 Worker 认领）、Leased（已被某个 Worker 排他租约持有）。'
        WHEN 'current_command_id' THEN '当前正在被租约持有执行的命令 ID（複合外键关联 agent_run_commands(run_id, command_id)，仅在 Leased 状态下非空）。'
        WHEN 'generation' THEN 'Run 调度租约 fencing 代数（每次认领时单调递增，用于拒绝旧慢节点过期写回）。'
        WHEN 'lease_owner' THEN '当前持有该 Run 调度租约的 Worker 实例标识（非 Leased 状态下为 NULL）。'
        WHEN 'lease_token' THEN '当前调度租约的排他校验令牌（UUID，非 Leased 状态下为 NULL）。'
        WHEN 'claimed_at' THEN '当前 Worker 成功认领该 Run 调度租约的时间戳（TIMESTAMPTZ）。'
        WHEN 'lease_expires_at' THEN '当前 Run 调度租约的到期时间（过期后允许其他 Worker 抢占恢复）。'
        WHEN 'heartbeat_at' THEN '当前 Worker 最近一次续期心跳时间戳（TIMESTAMPTZ）。'
        WHEN 'created_at' THEN '调度槽位记录首次创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '调度槽位状态或心跳最后更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 9. agent_business_operations：工具侧业务写操作幂等账本表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_business_operations' THEN CASE column_record.column_name
        WHEN 'operation_id' THEN '业务写操作全局唯一主键（UUID）。'
        WHEN 'scope_key' THEN '业务幂等隔离作用域键（如租户/账户边界，与 operation_name、idempotency_key 组成唯一约束）。'
        WHEN 'operation_name' THEN '业务写操作或可靠写工具的逻辑名称（1–200 字符）。'
        WHEN 'idempotency_key' THEN '调用方在当前业务作用域与操作名下提供的幂等键（1–200 字符）。'
        WHEN 'request_hash' THEN '规范化业务请求参数的 SHA-256 哈希（64 位十六进制），用于拦截同幂等键篡改参数的冲突调用。'
        WHEN 'status' THEN '业务操作状态：Executing（事务执行中过渡态）或 Succeeded（已与业务变更及 Outbox 事件同事务成功提交）。'
        WHEN 'result_json' THEN '业务写操作成功提交后的返回值快照（JSONB），供幂等重放直接返回而无需二次执行业务副作用。'
        WHEN 'run_id' THEN '触发该业务写操作的源 Agent 运行 ID（UUID）。'
        WHEN 'tool_call_id' THEN '触发该业务写操作的工具调用标识（call_id）。'
        WHEN 'created_at' THEN '业务操作记录创建时间（TIMESTAMPTZ）。'
        WHEN 'completed_at' THEN '业务操作完成提交的时间戳（Succeeded 状态下非空）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 10. agent_outbox_events：事务性发件箱事件表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_outbox_events' THEN CASE column_record.column_name
        WHEN 'event_id' THEN 'Outbox 事务事件全局唯一主键（UUID，在跨进程重试投递期间保持不变以供下游去重）。'
        WHEN 'operation_id' THEN '产出该事件的源业务操作标识（外键关联 agent_business_operations.operation_id，禁止级联误删）。'
        WHEN 'run_id' THEN '触发该事件的源 Agent 运行标识（UUID）。'
        WHEN 'tool_call_id' THEN '触发该事件的工具调用标识（call_id）。'
        WHEN 'scope_key' THEN '事件所属业务隔离作用域键。'
        WHEN 'ordinal' THEN '同一次业务操作（operation_id）内产出的事件顺序号（从 0 开始，与 operation_id 唯一约束）。'
        WHEN 'destination' THEN '事件投递目标逻辑通道或主题名称（1–200 字符）。'
        WHEN 'event_type' THEN '领域事件类型名称（1–200 字符）。'
        WHEN 'aggregate_type' THEN '事件关联的业务聚合根类型名称（1–100 字符）。'
        WHEN 'aggregate_id' THEN '事件关联的业务聚合根实例标识（1–200 字符）。'
        WHEN 'partition_key' THEN '消息保序投递使用的分区路由键（1–200 字符）。'
        WHEN 'payload' THEN '待投递的业务事件结构化正文（JSONB）。'
        WHEN 'headers' THEN '随事件投递的传输层元数据头信息（JSONB 对象，默认为空对象 {}）。'
        WHEN 'status' THEN 'Outbox 投递状态：Pending（待投递）、Publishing（Worker 租约认领投递中）、Published（已成功投递）、DeadLetter（投递耗尽重试进入死信）。'
        WHEN 'attempt' THEN '已尝试投递该事件的次数（从 0 开始计数）。'
        WHEN 'generation' THEN 'Outbox 投递租约 fencing 代数（每次认领递增）。'
        WHEN 'available_at' THEN '事件最早可被 Outbox Worker 认领投递的时间戳（支持退避重试）。'
        WHEN 'lease_owner' THEN '当前持有投递租约的 Outbox Worker 节点标识（仅 Publishing 状态下非空）。'
        WHEN 'lease_token' THEN '当前投递租约的排他校验令牌（UUID，仅 Publishing 状态下非空）。'
        WHEN 'lease_expires_at' THEN '当前投递租约过期时间戳（仅 Publishing 状态下非空）。'
        WHEN 'heartbeat_at' THEN '当前投递租约最后一次心跳续期时间（TIMESTAMPTZ）。'
        WHEN 'last_failure' THEN '最近一次投递失败的错误原因摘要。'
        WHEN 'created_at' THEN 'Outbox 事件随业务事务写入的时间戳（TIMESTAMPTZ）。'
        WHEN 'published_at' THEN '事件成功投递至目标通道并确认的时间戳（Published 状态下非空）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 11. agent_inbox_messages：下游消费者收件箱幂等表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_inbox_messages' THEN CASE column_record.column_name
        WHEN 'consumer_name' THEN '下游消费者逻辑名称（1–200 字符，与 message_id 组成复合主键）。'
        WHEN 'message_id' THEN '上游消息唯一标识（UUID，通常对应 Outbox 的 event_id）。'
        WHEN 'event_type' THEN '接收到的领域事件类型名称（1–200 字符）。'
        WHEN 'message_hash' THEN '消息关键载荷的 SHA-256 哈希（64 位十六进制），防止同 message_id 下内容不一致的篡改重放。'
        WHEN 'status' THEN '消费者处理状态：Processing（事务内处理中）或 Succeeded（已与下游业务变更同事务提交完成）。'
        WHEN 'result_json' THEN '消费者成功处理后的结构化回执（JSONB，Succeeded 状态下非空，用于重复投递时幂等短路返回）。'
        WHEN 'received_at' THEN '消费者首次接收并登记该消息的时间戳（TIMESTAMPTZ）。'
        WHEN 'processed_at' THEN '消费者完成业务处理并提交事务的时间戳（Succeeded 状态下非空）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 12. agent_compensations：显式 Saga 业务补偿计划表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_compensations' THEN CASE column_record.column_name
        WHEN 'compensation_id' THEN 'Saga 补偿计划全局唯一主键（UUID）。'
        WHEN 'operation_id' THEN '对应的正向业务写操作标识（唯一外键关联 agent_business_operations.operation_id，一操作最多一条补偿计划）。'
        WHEN 'run_id' THEN '所属 Agent 运行标识（UUID）。'
        WHEN 'scope_key' THEN '补偿执行所属业务隔离作用域键。'
        WHEN 'handler_name' THEN '负责执行该补偿动作的已注册 CompensationHandler 名称（1–200 字符）。'
        WHEN 'payload' THEN '执行逆向补偿动作所需的上下文参数载荷（JSONB）。'
        WHEN 'status' THEN '补偿生命周期状态：Registered（随正向操作预注册但未激活）、Pending（已激活待补偿 Worker 执行）、Running（Worker 租约执行中）、Succeeded（补偿成功）、Cancelled（无需补偿已取消）、DeadLetter（补偿失败超限进入死信）。'
        WHEN 'attempt' THEN '补偿动作已尝试执行的次数（从 0 开始计数）。'
        WHEN 'generation' THEN '补偿执行租约 fencing 代数（每次认领递增）。'
        WHEN 'available_at' THEN '补偿任务最早允许被 Worker 认领执行的时间戳（TIMESTAMPTZ）。'
        WHEN 'lease_owner' THEN '当前持有补偿执行租约的 Worker 节点标识（仅 Running 状态下非空）。'
        WHEN 'lease_token' THEN '当前补偿执行租约的排他令牌（UUID，仅 Running 状态下非空）。'
        WHEN 'lease_expires_at' THEN '当前补偿执行租约的过期时间（仅 Running 状态下非空）。'
        WHEN 'heartbeat_at' THEN '当前补偿执行租约最后一次心跳续期时间（TIMESTAMPTZ）。'
        WHEN 'last_failure' THEN '最近一次补偿执行失败的错误摘要。'
        WHEN 'created_at' THEN '补偿计划随正向事务预注册的时间戳（TIMESTAMPTZ）。'
        WHEN 'completed_at' THEN '补偿计划到达终态（Succeeded 或 Cancelled）的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 13. agent_embedding_cache：向量化精确缓存表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_embedding_cache' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '缓存隔离租户标识（与 purpose、provider、model、dimension、key_version、content_hash 组成 7 列复合主键）。'
        WHEN 'purpose' THEN '向量化用途分类：query（在线查询向量化）、indexing（知识库入库构建索引）、memory（长期记忆向量化）。'
        WHEN 'provider' THEN '向量化模型供应商标识（1–200 字符）。'
        WHEN 'model' THEN '向量化模型名称（1–200 字符）。'
        WHEN 'dimension' THEN '向量维度（正整数，CHECK 强制校验与 embedding 数组实际长度一致）。'
        WHEN 'key_version' THEN '缓存键规范化与预处理算法版本标识（1–100 字符，升级归一化规则时平滑失效旧缓存）。'
        WHEN 'content_hash' THEN '待向量化输入文本经规范化后的 SHA-256 哈希（64 位小写十六进制，不落盘原始文本）。'
        WHEN 'embedding' THEN '缓存的单精度浮点向量数组（REAL[]，数组基数必须等于 dimension）。'
        WHEN 'expires_at' THEN '该条向量缓存的过期时间（TIMESTAMPTZ，支持按租户策略定期清理过期项）。'
        WHEN 'created_at' THEN '缓存条目首次写入时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '缓存条目最后刷新或命中更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 14. agent_embedding_quota_windows：向量化租户配额时间窗口表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_embedding_quota_windows' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '配额统计所属租户标识（与 window_millis、window_start 组成复合主键）。'
        WHEN 'window_millis' THEN '配额滑动/固定窗口的时长（毫秒，正整数，如 60000 表示 1 分钟窗口、86400000 表示 1 天窗口）。'
        WHEN 'window_start' THEN '当前统计时间窗口的对齐起始时间戳（TIMESTAMPTZ）。'
        WHEN 'requests' THEN '当前时间窗口内该租户已累计消耗的 Embedding API 请求次数（>= 0）。'
        WHEN 'texts' THEN '当前时间窗口内该租户已累计消耗的向量化文本分段条数（>= 0）。'
        WHEN 'characters' THEN '当前时间窗口内该租户已累计消耗的向量化输入字符总数（>= 0）。'
        WHEN 'created_at' THEN '时间窗口计数器行首次创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '时间窗口计数器最后累加更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 15. agent_embedding_quota_reservations：向量化配额请求幂等预留账本表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_embedding_quota_reservations' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '所属租户标识（与 request_id 组成复合主键，并与配额窗口表建立级联删除外键）。'
        WHEN 'request_id' THEN '租户内唯一标识单次向量化计费请求的幂等键（1–500 字符）。'
        WHEN 'request_hash' THEN '本次向量化请求关键参数的 SHA-256 哈希（64 位十六进制），防止同 request_id 下篡改负载。'
        WHEN 'purpose' THEN '本次向量化请求的业务用途：query（查询）、indexing（建索引）、memory（记忆）。'
        WHEN 'window_millis' THEN '所归属扣减的配额窗口时长（毫秒，外键关联 agent_embedding_quota_windows）。'
        WHEN 'window_start' THEN '所归属扣减的配额窗口起始时间（TIMESTAMPTZ，外键关联 agent_embedding_quota_windows）。'
        WHEN 'requests' THEN '本次请求记入配额窗口的请求次数增量（正整数）。'
        WHEN 'texts' THEN '本次请求记入配额窗口的文本条数增量（正整数）。'
        WHEN 'characters' THEN '本次请求记入配额窗口的字符数增量（正整数）。'
        WHEN 'created_at' THEN '配额预留账本记录创建时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 16. agent_eval_snapshots：离线评测不可变快照表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_eval_snapshots' THEN CASE column_record.column_name
        WHEN 'evaluation_id' THEN '单次评测执行全局唯一主键（1–160 位字母数字及 ._- 字符，同 ID 严格幂等不可覆盖篡改）。'
        WHEN 'schema_version' THEN '评测快照序列化契约版本号（固定为 1）。'
        WHEN 'suite_kind' THEN '评测套件大类：Agent（智能体综合）、AgentReliability（可靠性与恢复）、HarnessComparison（Harness 对比）、Rag（知识检索）、ContextCompression（上下文压缩）。'
        WHEN 'suite_id' THEN '具体评测套件唯一标识（1–160 字符）。'
        WHEN 'dataset_id' THEN '评测所用基准数据集标识（1–160 字符）。'
        WHEN 'dataset_version' THEN '评测所用基准数据集版本号（1–160 字符）。'
        WHEN 'harness_version' THEN '执行评测的框架或测试底座版本标识（1–160 字符）。'
        WHEN 'provider' THEN '被测模型供应商名称（纯检索或确定性组件评测时可为 NULL）。'
        WHEN 'model' THEN '被测模型标识（纯检索或确定性组件评测时可为 NULL）。'
        WHEN 'pricing_version' THEN '评测成本核算所使用的费率表版本标识（可选）。'
        WHEN 'commit_sha' THEN '产出该评测快照时的代码仓库 Git Commit SHA（可选）。'
        WHEN 'started_at' THEN '评测套件开始执行时间戳（TIMESTAMPTZ）。'
        WHEN 'finished_at' THEN '评测套件完成执行时间戳（TIMESTAMPTZ，必须 >= started_at）。'
        WHEN 'finished_epoch_second' THEN 'finished_at 对应的纪元秒数（BIGINT），配合 finished_nano 实现跨平台纳秒级确定性排序。'
        WHEN 'finished_nano' THEN 'finished_at 秒内的纳秒偏移量（0–999,999,999），弥补 PostgreSQL TIMESTAMPTZ 微秒精度截断。'
        WHEN 'passed' THEN '本次评测是否通过门禁阈值（TRUE 为通过，FALSE 为未通过）。'
        WHEN 'pass_rate' THEN '评测用例通过率（0.0 到 1.0 之间的非 NaN 双精度浮点数）。'
        WHEN 'snapshot_sha256' THEN 'snapshot_payload 原始字节流的 SHA-256 完整性校验哈希（64 位小写十六进制）。'
        WHEN 'snapshot_payload' THEN '规范化序列化后的 EvalSuiteSnapshot JSON 原始文本（用于精确哈希校验与字节级还原）。'
        WHEN 'snapshot_json' THEN '与 snapshot_payload 完全等价的 JSONB 对象，支持按结构化指标字段进行 SQL 查询对比。'
        WHEN 'created_at' THEN '评测快照写入数据库的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 17. agent_workflow_checkpoints：声明式工作流耐久恢复检查点表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_workflow_checkpoints' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '声明式工作流运行实例全局唯一主键（UUID）。'
        WHEN 'schema_version' THEN '工作流检查点序列化契约版本号（固定为 1）。'
        WHEN 'workflow_id' THEN '工作流拓扑定义标识（1–160 位字母数字及 ._- 字符）。'
        WHEN 'definition_version' THEN '工作流拓扑定义版本号（正整数，防止跨不兼容定义版本错误恢复）。'
        WHEN 'session_id' THEN '工作流运行归属的业务会话标识（UUID）。'
        WHEN 'cursor_kind' THEN '工作流当前执行游标状态：At（停留在某个待执行/执行中节点 node_id）或 Completed（整条工作流已完工）。'
        WHEN 'node_id' THEN '当前游标所在的节点标识（当 cursor_kind = ''At'' 时非空，当 cursor_kind = ''Completed'' 时必须为 NULL）。'
        WHEN 'step' THEN '工作流已推进的单调递增步数序号（从 0 开始，仅允许在同身份内向更大 step 演进）。'
        WHEN 'checkpoint_sha256' THEN 'checkpoint_payload 的 SHA-256 完整性哈希（64 位小写十六进制）。'
        WHEN 'checkpoint_payload' THEN '工作流检查点规范化 JSON 原文（用于字节级 SHA-256 校验）。'
        WHEN 'checkpoint_json' THEN '工作流完整恢复状态快照（JSONB 对象，与 checkpoint_payload::jsonb 严格一致）。'
        WHEN 'updated_at' THEN '检查点最后提交更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 18. agent_workflow_node_executions：声明式工作流节点执行台账表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_workflow_node_executions' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '所属工作流运行实例标识（与 step、node_id 组成复合主键）。'
        WHEN 'workflow_id' THEN '工作流拓扑定义标识。'
        WHEN 'definition_version' THEN '工作流拓扑定义版本号（正整数）。'
        WHEN 'session_id' THEN '所属会话标识（UUID）。'
        WHEN 'node_id' THEN '当前执行的图节点标识。'
        WHEN 'step' THEN '工作流全局执行步数序号（从 0 开始）。'
        WHEN 'visit' THEN '当前节点在该次运行中第几次被访问执行（从 1 开始，支持循环回路节点追踪）。'
        WHEN 'status' THEN '节点执行三阶段状态：Running（持有租约执行中）、Prepared（节点输出已落盘待与检查点合并提交）、Committed（已与工作流检查点同事务提交完成）。'
        WHEN 'generation' THEN '节点执行分布式 fencing 代数（从 1 开始单调递增，防止过期 Worker 覆盖结果）。'
        WHEN 'lease_owner' THEN '当前或最后认领该节点执行的 Worker 实例标识。'
        WHEN 'lease_token' THEN '当前或最后认领该节点执行的排他租约令牌（UUID）。'
        WHEN 'claimed_at' THEN '节点执行租约被认领的时间戳（TIMESTAMPTZ）。'
        WHEN 'lease_expires_at' THEN '节点执行租约到期时间（Running/Prepared 状态下非空，Committed 后置空释放）。'
        WHEN 'heartbeat_at' THEN '节点执行租约最近一次心跳续期时间（Running/Prepared 状态下非空，Committed 后置空）。'
        WHEN 'outcome_sha256' THEN '节点执行产出 outcome_payload 的 SHA-256 校验哈希（Prepared 与 Committed 状态下非空）。'
        WHEN 'outcome_payload' THEN '节点执行产出的规范化 JSON 原文（Prepared 与 Committed 状态下非空）。'
        WHEN 'outcome_json' THEN '节点执行产出的结构化结果（JSONB 对象，与 outcome_payload::jsonb 一致，支撑崩溃后无需重跑节点直接恢复 commit）。'
        WHEN 'created_at' THEN '节点执行台账行创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '节点执行台账最后更新时间（TIMESTAMPTZ）。'
        WHEN 'completed_at' THEN '节点执行推进至 Committed 终态的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 19. agent_workflow_waits：声明式工作流耐久等待表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_workflow_waits' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '所属工作流运行实例标识（与 step、node_id 组成复合主键并外键关联节点执行台账）。'
        WHEN 'step' THEN '注册该耐久等待时的工作流步数序号。'
        WHEN 'node_id' THEN '触发并挂起等待的工作流节点标识。'
        WHEN 'workflow_id' THEN '所属工作流拓扑定义标识。'
        WHEN 'definition_version' THEN '所属工作流拓扑定义版本号。'
        WHEN 'session_id' THEN '所属工作流会话标识（UUID）。'
        WHEN 'condition_kind' THEN '等待唤醒条件类型：Signal（等待外部命名信号，附带超时 deadline）或 Timer（纯耐久定时器等待）。'
        WHEN 'signal_name' THEN '期望接收的外部信号名称（condition_kind = ''Signal'' 时非空，''Timer'' 时必须为 NULL）。'
        WHEN 'deadline' THEN '等待超时截止时间（TIMESTAMPTZ，Signal 超时或 Timer 到期时由此触发唤醒）。'
        WHEN 'status' THEN '等待状态机：Pending（挂起等待中）、Signaled（已收到有效信号待唤醒 Worker 消费）、TimedOut（已超时待唤醒 Worker 消费）、Consumed（唤醒结果已推进工作流检查点并消费完毕）。'
        WHEN 'accepted_signal_id' THEN '被采纳并触发 Signaled 状态的外部信号唯一幂等标识（仅 Signaled/Consumed 信号唤醒时非空）。'
        WHEN 'accepted_signal_payload' THEN '被采纳外部信号的负载原文（最大 64KB，不在日志或时间线中明文暴露）。'
        WHEN 'accepted_signal_sha256' THEN 'accepted_signal_payload 的 SHA-256 哈希校验值（64 位小写十六进制）。'
        WHEN 'accepted_signal_authorization' THEN '被采纳外部信号调用方鉴权上下文的 SHA-256 指纹（64 位小写十六进制）。'
        WHEN 'signal_received_at' THEN '有效外部信号到达并被采纳的时间戳（TIMESTAMPTZ）。'
        WHEN 'created_at' THEN '耐久等待注册创建时间（TIMESTAMPTZ）。'
        WHEN 'resolved_at' THEN '等待条件达成（转入 Signaled 或 TimedOut）的时间戳（TIMESTAMPTZ）。'
        WHEN 'consumed_at' THEN '唤醒 Worker 完成下游节点恢复并将等待标记为 Consumed 的时间戳（TIMESTAMPTZ）。'
        WHEN 'wake_available_at' THEN '已决议等待项（Signaled/TimedOut）可被唤醒 Worker 认领的调度时间戳（支持退避重试）。'
        WHEN 'wake_generation' THEN '唤醒消费租约的 fencing 代数（Pending 时为 0，每次唤醒认领递增）。'
        WHEN 'wake_owner' THEN '当前持有唤醒消费租约的 Worker 节点标识。'
        WHEN 'wake_token' THEN '当前唤醒消费租约的排他校验令牌（UUID）。'
        WHEN 'wake_claimed_at' THEN '当前唤醒消费租约的认领时间戳（TIMESTAMPTZ）。'
        WHEN 'wake_lease_expires_at' THEN '当前唤醒消费租约的到期时间戳（TIMESTAMPTZ）。'
        WHEN 'wake_heartbeat_at' THEN '当前唤醒消费租约的最后心跳时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 20. agent_workflow_signals：声明式工作流外部信号去重回执表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_workflow_signals' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '目标工作流运行实例标识（与 wait_step、wait_node_id、signal_id 组成复合主键）。'
        WHEN 'wait_step' THEN '目标等待节点所在的工作流步数序号（外键关联 agent_workflow_waits）。'
        WHEN 'wait_node_id' THEN '目标等待节点标识（外键关联 agent_workflow_waits）。'
        WHEN 'signal_id' THEN '外部信号投递方提供的幂等信号 ID（1–200 字符，同一等待节点下唯一去重）。'
        WHEN 'signal_name' THEN '投递的信号名称（1–160 字符）。'
        WHEN 'payload' THEN '投递的信号负载原文（最大 64KB，受控存储，不进入公共日志或指标）。'
        WHEN 'payload_sha256' THEN '信号负载 payload 的 SHA-256 哈希值（64 位小写十六进制），用于检测同 signal_id 负载冲突。'
        WHEN 'authorization_fingerprint' THEN '信号发送方鉴权身份凭证的 SHA-256 指纹（64 位小写十六进制）。'
        WHEN 'disposition' THEN '信号投递处置结果：Accepted（首个命中并成功唤醒等待）、Late（等待已超时后迟到的信号）、AlreadyResolved（等待已被其他信号决议后的晚到信号）。'
        WHEN 'received_at' THEN '该信号请求被服务端接收并记录回执的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 21. agent_runtime_overrides：运行时动态治理覆盖配置表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_runtime_overrides' THEN CASE column_record.column_name
        WHEN 'version' THEN '运行时治理配置修订版本号（从 1 开始严格递增主键，最新行代表当前生效覆盖项）。'
        WHEN 'overrides' THEN '运行时动态覆盖参数快照（JSONB，如模型降级开关、并发限流或特定工具熔断配置）。'
        WHEN 'updated_by' THEN '执行本次运行时配置变更的已认证管理员或系统主体标识（1–200 字符）。'
        WHEN 'reason' THEN '本次运行时配置变更的操作原因说明（最长 512 字符，用于运维审计追踪）。'
        WHEN 'updated_at' THEN '该版本治理配置写入生效的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 22. agent_ingestion_jobs：知识入库异步任务主表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_ingestion_jobs' THEN CASE column_record.column_name
        WHEN 'job_id' THEN '知识入库异步任务全局唯一主键（UUID）。'
        WHEN 'tenant_id' THEN '入库任务归属的租户标识（1–200 字符）。'
        WHEN 'source_uri' THEN '待入库源文档的脱敏受控定位符（URI，禁止包含明文密钥或签名 Token）。'
        WHEN 'file_name' THEN '源文档展示文件名（1–400 字符）。'
        WHEN 'media_type' THEN '源文档 MIME 媒体类型（如 application/pdf、text/markdown 等）。'
        WHEN 'status' THEN '入库流水线阶段状态：Queued（排队中）、Loading（加载解析）、Chunking（语义切分）、Embedding（向量化）、Staging（写入暂存版本）、Activating（原子切换激活版本）、Completed（已完成）、Failed（失败）。'
        WHEN 'progress_percent' THEN '入库任务完成进度百分比（0–100 整数）。'
        WHEN 'document_id' THEN '关联的知识库逻辑文档 ID（對應 zyblw_agent_knowledge.agent_knowledge_documents.document_id）。'
        WHEN 'index_version' THEN '本次入库构建并激活的知识文档版本号（正整数，未进入构建阶段时可为 NULL）。'
        WHEN 'chunk_count' THEN '本次入库切分并写入的切片（Chunk）总条数（>= 0，切分完成前可为 NULL）。'
        WHEN 'failure_code' THEN '当 status = ''Failed'' 时的结构化失败原因码（非 Failed 状态下必须为 NULL）。'
        WHEN 'submitted_by' THEN '提交该入库任务的操作主体标识（1–200 字符）。'
        WHEN 'created_at' THEN '入库任务提交创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '入库任务阶段或进度最后更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 23. model_call_executions：大模型调用执行台账表
      -- =========================================================================
      WHEN column_record.table_name = 'model_call_executions' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '所属 Agent 运行标识（外键关联 agent_runs.run_id，与 request_id 组成复合主键）。'
        WHEN 'request_id' THEN '单次大模型推理请求在 Run 内的唯一标识（UUID）。'
        WHEN 'attempt' THEN '当前模型调用请求已尝试发送的次数（从 0 开始计数）。'
        WHEN 'status' THEN '模型调用执行状态：Prepared（请求已组装落盘）、Dispatched（已发出网络请求）、Succeeded（收到完整有效响应）、Failed（调用失败）、Unknown（进程中断后响应状态未知）。'
        WHEN 'provider' THEN '本次推理调用的模型供应商标识（如 openai、anthropic、deepseek 等）。'
        WHEN 'model' THEN '本次推理调用的具体模型标识。'
        WHEN 'capture_policy' THEN '模型输入输出落盘捕获策略：Disabled（不落盘载荷）、MetadataOnly（仅保存统计与哈希元数据）、Replayable（保存可重放完整载荷）。'
        WHEN 'fingerprint' THEN '规范化模型请求入参的 SHA-256 指纹（64 位小写十六进制），用于确定性重放校验与去重诊断。'
        WHEN 'message_count' THEN '本次模型请求上下文中包含的消息总条数（>= 0）。'
        WHEN 'tool_count' THEN '本次模型请求挂载的可选工具定义（Tool Schema）数量（>= 0）。'
        WHEN 'record_json' THEN '受 capture_policy 脱敏裁剪约束的模型调用执行详情记录（JSONB，包含 Token 用量、延迟、完成原因或错误信息）。'
        WHEN 'updated_at' THEN '模型调用台账记录最后更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 24. harness_goals：长程自主代理（Harness）目标表
      -- =========================================================================
      WHEN column_record.table_name = 'harness_goals' THEN CASE column_record.column_name
        WHEN 'goal_id' THEN 'Harness 顶层任务目标全局唯一主键（UUID）。'
        WHEN 'thread_id' THEN '目标归属的外部对话线程或工作区标识（1–255 字符）。'
        WHEN 'objective' THEN '自然语言描述的顶层任务目标说明（1–4000 字符）。'
        WHEN 'status' THEN '目标生命周期状态：Draft（草稿规划中）、Active（激活执行中）、Completed（目标已达成）、Cancelled（已取消）、Failed（执行失败终止）。'
        WHEN 'run_id' THEN '当前正在推进或最后关联的 Agent 运行 ID（可选 UUID）。'
        WHEN 'revision' THEN '目标记录乐观锁修订代数（从 1 开始严格递增）。'
        WHEN 'artifacts_json' THEN '该目标已产出的关联产物引用列表（JSONB 数组，默认为空数组 []）。'
        WHEN 'updated_at' THEN '目标状态或内容最后更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 25. harness_plans：长程自主代理（Harness）执行计划表
      -- =========================================================================
      WHEN column_record.table_name = 'harness_plans' THEN CASE column_record.column_name
        WHEN 'plan_id' THEN 'Harness 执行计划全局唯一主键（UUID）。'
        WHEN 'goal_id' THEN '所属 Harness 目标标识（外键关联 harness_goals.goal_id，级联删除）。'
        WHEN 'summary' THEN '执行计划整体策略摘要说明（1–4000 字符）。'
        WHEN 'todos_json' THEN '结构化待办步骤列表（JSONB，记录各子步骤标题、状态、依赖与验收结果）。'
        WHEN 'artifacts_json' THEN '计划执行过程中关联或产出的产物引用列表（JSONB 数组，默认为空数组 []）。'
        WHEN 'revision' THEN '执行计划乐观锁修订代数（从 1 开始严格递增）。'
        WHEN 'updated_at' THEN '执行计划最后修订时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 26. harness_skills：长程自主代理（Harness）技能注册表
      -- =========================================================================
      WHEN column_record.table_name = 'harness_skills' THEN CASE column_record.column_name
        WHEN 'skill_id' THEN '技能逻辑唯一标识（1–64 字符且不含 @ 符号，与 skill_version 组成复合主键）。'
        WHEN 'skill_version' THEN '技能语义化版本或版本标签（1–32 位字母数字及 ._- 字符）。'
        WHEN 'source' THEN '技能包来源路径或注册源标识（1–256 字符）。'
        WHEN 'trust' THEN '技能安全信任等级：Untrusted（未受信任外部技能）、Reviewed（经人工/规则审阅）、Trusted（平台内置可信技能）。'
        WHEN 'body' THEN '技能完整提示词指令与执行规范正文（1–16000 字符）。'
        WHEN 'fingerprint' THEN '技能正文与元数据的 SHA-256 完整性校验指纹（64 位小写十六进制）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 27. harness_interactions：长程自主代理（Harness）人机交互指令表
      -- =========================================================================
      WHEN column_record.table_name = 'harness_interactions' THEN CASE column_record.column_name
        WHEN 'interaction_id' THEN 'Harness 交互指令全局唯一主键（UUID）。'
        WHEN 'goal_id' THEN '所属 Harness 目标标识（外键关联 harness_goals.goal_id，级联删除）。'
        WHEN 'run_id' THEN '交互指令关联或作用的特定 Agent 运行 ID（可选 UUID）。'
        WHEN 'kind' THEN '交互指令类别：Steer（运行中方向调整/纠偏）、FollowUp（阶段完成后追加追问）、UserMessage（常规用户消息输入）。'
        WHEN 'body' THEN '用户输入的交互指令或消息文本正文（1–4000 字符）。'
        WHEN 'sequence' THEN '同一目标（goal_id）内从 1 开始严格递增的交互顺序号（与 goal_id 组成唯一约束）。'
        WHEN 'created_at' THEN '交互指令提交创建时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 28. harness_goal_budgets：长程自主代理（Harness）目标级多维预算池表
      -- =========================================================================
      WHEN column_record.table_name = 'harness_goal_budgets' THEN CASE column_record.column_name
        WHEN 'goal_id' THEN '受控的 Harness 目标主键（外键关联 harness_goals.goal_id，级联删除，一目标一预算池）。'
        WHEN 'max_runs' THEN '该目标允许启动的最大 Agent 运行（Run）总次数上限（> 0）。'
        WHEN 'max_model_calls' THEN '该目标允许发起的最大模型推理调用总次数上限（> 0）。'
        WHEN 'max_tool_calls' THEN '该目标允许执行的最大工具调用总次数上限（> 0）。'
        WHEN 'max_input_tokens' THEN '该目标允许消耗的最大输入 Token 总量上限（> 0）。'
        WHEN 'max_output_tokens' THEN '该目标允许消耗的最大输出 Token 总量上限（> 0）。'
        WHEN 'max_total_tokens' THEN '该目标允许消耗的最大总 Token 上限（> 0 且不超过 max_input_tokens + max_output_tokens）。'
        WHEN 'max_estimated_cost' THEN '该目标允许消耗的最大估算成本金额上限（可选 NUMERIC，> 0）。'
        WHEN 'reserved_runs' THEN '当前正在执行中的 Run 已预留冻结的运行次数（>= 0）。'
        WHEN 'reserved_model_calls' THEN '当前活跃运行已预留冻结的模型调用次数额度（>= 0）。'
        WHEN 'reserved_tool_calls' THEN '当前活跃运行已预留冻结的工具调用次数额度（>= 0）。'
        WHEN 'reserved_input_tokens' THEN '当前活跃运行已预留冻结的输入 Token 额度（>= 0）。'
        WHEN 'reserved_output_tokens' THEN '当前活跃运行已预留冻结的输出 Token 额度（>= 0）。'
        WHEN 'reserved_total_tokens' THEN '当前活跃运行已预留冻结的总 Token 额度（>= 0）。'
        WHEN 'reserved_estimated_cost' THEN '当前活跃运行已预留冻结的估算成本额度（>= 0）。'
        WHEN 'consumed_runs' THEN '已结算运行累计实际消耗的 Run 次数（>= 0）。'
        WHEN 'consumed_model_calls' THEN '已结算运行累计实际消耗的模型调用总次数（>= 0）。'
        WHEN 'consumed_tool_calls' THEN '已结算运行累计实际消耗的工具调用总次数（>= 0）。'
        WHEN 'consumed_input_tokens' THEN '已结算运行累计实际消耗的输入 Token 总数（>= 0）。'
        WHEN 'consumed_output_tokens' THEN '已结算运行累计实际消耗的输出 Token 总数（>= 0）。'
        WHEN 'consumed_total_tokens' THEN '已结算运行累计实际消耗的总 Token 数（>= 0）。'
        WHEN 'consumed_estimated_cost' THEN '已结算运行累计实际消耗的估算总成本（>= 0）。'
        WHEN 'created_at' THEN '目标预算池创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '预算池预留或结算最后更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 29. harness_budget_reservations：单次运行预算预留与结算表
      -- =========================================================================
      WHEN column_record.table_name = 'harness_budget_reservations' THEN CASE column_record.column_name
        WHEN 'run_id' THEN '申请预算预留的 Agent 运行唯一主键（UUID）。'
        WHEN 'goal_id' THEN '归属扣减的 Harness 目标预算池标识（外键关联 harness_goal_budgets.goal_id，级联删除）。'
        WHEN 'limits_json' THEN '本次运行从目标预算池切出并锁定的单次运行预算上限快照（JSONB 对象）。'
        WHEN 'status' THEN '预算预留生命周期状态：Reserved（运行中已预留冻结）、Settled（运行正常结束已按实际用量结算）、Released（运行未实际消耗已全额释放预留）、Exceeded（运行因超预算被熔断结算）。'
        WHEN 'usage_json' THEN '运行结束结算时的实际多维资源消耗明细（JSONB 对象；Reserved/Released 状态下必须为 NULL，Settled/Exceeded 状态下非空）。'
        WHEN 'created_at' THEN '预算预留创建时间戳（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '预算预留状态变更或结算完成时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 30. agent_artifacts：Agent 产物逻辑主表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_artifacts' THEN CASE column_record.column_name
        WHEN 'scope_kind' THEN '产物隔离作用域类型：session（会话级产物）、user（用户级长期产物）、run（单次运行局部产物）。'
        WHEN 'scope_key' THEN '产物所属作用域的规范化键值（1–500 字符，与 scope_kind、name 组成复合主键）。'
        WHEN 'name' THEN '产物在同一作用域内的逻辑文件/对象名称（1–255 字符）。'
        WHEN 'latest_version' THEN '当前产物的最新版本号（正整数，每次保存新版本时递增）。'
        WHEN 'created_at' THEN '产物逻辑身份首次创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '产物最新一次写入新版本的时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 31. agent_artifact_versions：Agent 产物不可变版本内容表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_artifact_versions' THEN CASE column_record.column_name
        WHEN 'scope_kind' THEN '产物所属作用域类型（session/user/run，外键关联 agent_artifacts，级联删除）。'
        WHEN 'scope_key' THEN '产物所属作用域键值（外键关联 agent_artifacts）。'
        WHEN 'name' THEN '产物逻辑名称（外键关联 agent_artifacts）。'
        WHEN 'version' THEN '产物不可变版本号（从 1 开始的正整数，与 scope_kind、scope_key、name 组成 4 列复合主键）。'
        WHEN 'media_type' THEN '该版本产物内容的 MIME 媒体类型（如 text/plain、application/json、image/png）。'
        WHEN 'byte_size' THEN '该版本产物二进制内容的精确字节大小（>= 0）。'
        WHEN 'sha256' THEN '该版本产物二进制字节流的 SHA-256 校验和（64 位小写十六进制）。'
        WHEN 'metadata_json' THEN '该版本产物的扩展描述元数据（JSONB）。'
        WHEN 'bytes' THEN '该版本产物的原始二进制内容（BYTEA；外部存储引用或已卸载内容时可为 NULL）。'
        WHEN 'created_at' THEN '该产物版本写入持久化的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =========================================================================
      -- 32. agent_artifact_audit：Agent 产物治理不可变审计表
      -- =========================================================================
      WHEN column_record.table_name = 'agent_artifact_audit' THEN CASE column_record.column_name
        WHEN 'audit_id' THEN '产物治理审计记录全局唯一主键（UUID）。'
        WHEN 'action' THEN '产物治理动作类型：save（保存新版本）、read（读取内容）、delete（删除指定产物或版本）、purge（按作用域批量清理）。'
        WHEN 'scope_kind' THEN '被操作产物所属的作用域类型：session（会话）、user（用户）、run（运行）。'
        WHEN 'scope_key' THEN '被操作产物所属的作用域键值。'
        WHEN 'name_hash' THEN '被操作产物名称（name）的 SHA-256 哈希值（64 位小写十六进制，避免在审计表中暴露敏感文件名）。'
        WHEN 'version' THEN '治理动作针对的具体产物版本号（整域清理或全版本删除时可为 NULL）。'
        WHEN 'reason_code' THEN '执行本次产物操作的业务或治理原因码（1–80 字符）。'
        WHEN 'occurred_at' THEN '产物治理动作发生的业务时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      ELSE NULL
    END;

    IF comment_text IS NULL THEN
      RAISE EXCEPTION 'zyblw-agent column %.%.% is missing a Chinese COMMENT mapping',
        target_schema, column_record.table_name, column_record.column_name;
    END IF;

    EXECUTE format(
      'COMMENT ON COLUMN %I.%I.%I IS %L',
      target_schema,
      column_record.table_name,
      column_record.column_name,
      comment_text
    );
  END LOOP;
END
$comments$;
