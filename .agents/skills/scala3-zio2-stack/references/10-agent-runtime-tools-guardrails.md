# 10. 智能体运行时、工具契约与安全防线

用于开发或审查智能体循环（Agent Loop）、工具调用契约、Prompt 编译防注入、Guardrail 守卫与模型治理策略。

## 一、 纯内核与驱动分离 (Functional Kernel & Runtime Driver / ADR-0028)

遵循 ADR-0028 设计，将智能体运行时严格拆分为**确定性纯内核**与**副作用驱动器**：

1. **纯内核 (`AgentKernel`)**：
   - 核心职责是纯函数式的决策与状态转移：`(AgentState, RuntimeEvent) => KernelTransition`。
   - 内核只产生动作意图（`NextAction: CallModel | ExecuteTools | Suspend | Complete | Terminate`），**严禁在内核中直接发起网络请求、查询数据库或执行系统工具**。
   - 收益：内核的所有决策逻辑可在毫秒级纯内存单元测试中重现与回放。
2. **运行时驱动器 (`AgentRuntimeDriver`)**：
   - 负责驱动具体副作用：调用大模型 API、在安全边界内分发工具执行、提交 CAS 持久化事务、发送遥测事件。

## 二、 上下文权威与 Prompt 安全编译 (ADR-0029 & 防提示词注入)

1. **System 提示词权威（System Instruction Authority）**：
   - System 提示词拥有最高且不可篡改的控制权，系统指令在会话生命周期内保持稳定。
   - **严禁外部提升**：来自 RAG 检索知识片段、用户消息、外部 OCR 文本、网页内容或工具返回值的文本，**永远只允许作为不可信的数据信封（Data Envelopes）注入 User 作用域**，绝对禁止提升为 System 指令。
2. **信封边界转义与注入监控**：
   - `PromptCompiler` 负责对外部数据块进行定界与信封包装，防止外部文本中伪造的 `System:` 伪指令或提权攻击。
   - 在数据进入模型推理上下文之前，必须经由 `PromptInjectionMonitor` 与 `UntrustedContentMonitor` 过滤典型越权短语。

## 三、 工具调用契约与沙盒执行 (Tool Contracts & ToolExecutor)

1. **意图与执行解耦**：
   - 大模型返回的 `ToolCall` 仅仅是一个外部意图（Command）。
   - 在执行前，必须经由 `ToolAuthorizationGate` 核验调用权限、审批策略（`ApprovalPolicy`）与执行环境隔离域（`ExecutionEnvironmentId`）。
2. **严格模式参数解析 (Parse, Don't Validate)**：
   - 工具入参必须声明严格 Schema，并在边界一次性反序列化为强类型领域对象；解析失败直接返回结构化错误，不执行具体工具函数。
3. **工具输出有界化与外部化 (Externalization)**：
   - 工具输出结果必须有明确的最大长度限制；
   - 超过大小阈值（如 4KB / 8KB）的结果，必须由 `ToolExecutor.externalize` 自动将正文外置为 Run 作用域的 Artifact 存储，仅向大模型上下文回传受限的安全预览（Preview），防止模型上下文窗口被大文本击穿。

## 四、 守卫引擎分级拦截 (GuardrailEngine)

守卫规则贯穿于智能体执行的全生命周期：

- **拦截阶段（GuardrailStage）**：
  - `Input`：在大模型推理调用前，校验用户输入与召回片段；
  - `ToolExecution`：在具体工具执行前/后，核验参数与返回值的合规性；
  - `Output`：在向用户展示最终回答前，核验医疗/业务安全性与免责声明。
- **拦截语义（GuardrailAction）**：
  - `Blocking`（阻断）：直接中断执行流，Fail-Closed 并记录拦截原因；
  - `Monitoring`（旁路监控）：仅记录审计指标，放行执行，用于灰度观测。

## 五、 凭据脱敏网关与事故包治理 (IncidentPack)

1. **零硬编码凭据**：
   - 模型 Provider 的 API Key 严禁以明文形式出现在代码、配置、数据库或状态快照中；统一使用 `env:VARIABLE_NAME` 引用与存在性布尔标记。
2. **事故排查包脱敏（IncidentPackExporter）**：
   - 在系统发生故障导出现场事故包用于排查时，**绝对禁止包含原始 Prompt、用户密码或敏感私有正文**；
   - 导出器内置敏感子串检测，一旦在导出流中发现凭据特征，直接 Fail-Closed 中断序列化，防止事故包变成泄密通道。

## 六、 模型路由、成本硬顶与故障切换 (Model Routing & Governance)

1. **严格重试与降级准则**：
   - 仅对网络超时、瞬时连接失败、供应商 429 速率限制等**可重试错误**触发备用模型切换；
   - 遇到鉴权错误（401/403）、协议格式不匹配或参数严重越界时，**严禁盲目切换模型**，直接向调用方报告明确配置故障。
2. **用量与成本预算门禁**：
   - 必须精确核算并记录输入 Token、输出 Token 以及 Prompt Cache 读写 Token；
   - 执行前核验费用预算，超出硬上限时立即触发 `BudgetExceeded` 熔断，保护账户资金安全。
