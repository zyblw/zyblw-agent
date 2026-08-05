# ADR 0017: agent-dashboard 前端控制台架构与设计方案

> 状态：Accepted (设计方案)  
> 日期：2026-08-06  
> 事实来源：`docs/architecture.md`、`docs/observability.md`、`docs/usage-guide.md`、`docs/maturity-and-roadmap.md`

## 1. 概述与愿景

`zyblw-agent` 是一个基于 Scala 3 / ZIO 2 的高可靠、强契约、Code-First 企业级智能体框架。为解决开发调试、长任务追溯、RAG 谱系验证以及分布式 Worker 监控中的“黑盒化”痛点，本 ADR 正式定义框架内置前端控制台（`zyblw-agent-dashboard`）的全局架构、技术选型与交互设计。

### 1.1 核心边界

- **解耦业务端 UI**：终端用户面向的具体业务 UI（如客服对话框、Copilot 侧边栏、医疗问答界面）由业务团队自行开发；控制台专注于**框架级开发者与运维管理（Developer & SRE Console）**。
- **只读与受控控制面**：控制台不直接打入数据库或执行任意副作用；所有数据通过 `agent-zio-http` 的 `/api/v1` 标准 Endpoint、SSE 事件流和只读 Admin 快照 API 交互。
- **隐私与脱敏首重**：遵循 `Redactor` 默认规则，控制台视图不泄漏敏感 API Key、Prompt 隐式数据、用户 PII 或数据库凭据。

---

## 2. 全网开源标杆分析与模式融合

结合全球领先开源智能体框架与 LLMOps 平台的卓越设计，`zyblw-agent-dashboard` 融合以下核心交互优点：

```text
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             标杆框架优点融合矩阵                                 │
├──────────────────┬─────────────────────────────────────┬─────────────────────────┤
│ 标杆框架          │ 卓越交互与功能亮点                   │ zyblw-agent 融合设计    │
├──────────────────┼─────────────────────────────────────┼─────────────────────────┤
│ LangGraph Studio │ 节点执行时间线、状态重放、暂停审批  │ Step Timeline & Approval│
│ Dify             │ 检索测试沙盒、结构化 Chunk 预览      │ RAG Hybrid Search Sandbox│
│ Arize Phoenix    │ OTel Trace 树、Token/Cost 维度拆解   │ Token Cost & Trace Inspector│
│ RAGFlow          │ PDF 双栏渲染与 Bounding Box 原文高亮 │ PDF Page/BBox Lineage Overlay│
│ LangSmith        │ 评测 pass@k 趋势大盘与 Fail-Closed 门禁 │ Eval Trends & Release Gate Panel│
└──────────────────┴─────────────────────────────────────┴─────────────────────────┘
```

---

## 3. 技术栈选型与组件生态 (Tech Stack)

控制台采用现代主流的 Web 技术栈，保证极高的开发效率、极致的视觉质感与平滑的交互体验：

```text
zyblw-agent-dashboard 架构栈
├── 基础框架: Next.js 15 (App Router, React 19, TypeScript 5.x)
├── 样式与 UI 系统: Tailwind CSS v4 + Shadcn UI (Radix UI Primitives) + Lucide Icons
├── 工作流/图可视化: React Flow (@xyflow/react v12)
├── 数据图表与指标: Tremor / Recharts
├── PDF 渲染与几何高亮: pdfjs-dist + HTML5 Canvas BoundingBox Overlay
├── 状态管理与数据流: TanStack Query v5 (React Query) + Zustand
└── 构建与嵌入部署: Next.js Static Export (SSG) / Docker Container
```

---

## 4. 部署模式与安全隔离

设计支持两种灵活部署模式：

1. **单体嵌入模式 (Embedded Dev Console)**：
   使用 `next build` 导出为纯静态资源（HTML/JS/CSS），直接打包进 `zyblw-agent-zio-http` 模块，由 ZIO HTTP 静态文件 Handler 托管于 `/dashboard`。无需额外的 Node.js 运行环境。
2. **独立运维模式 (Standalone Admin Console)**：
   作为一个独立的 Next.js Node.js 容器服务部署，可通过环境变量配置连接一个或多个远端 `zyblw-agent` HTTP Host 实例，适合多集群统一运维。

---

## 5. 四大核心功能看板设计蓝图

控制台划分为 **4 大核心页面看板**：

### 5.1 🕵️ 看板一：智能体运行追溯与调试器 (Run Inspector & Live Debugger)

#### 布局架构
使用三栏式现代 IDE 布局：
- **左栏 (Thread & Run Selector)**：会话 Thread 树、按 AgentId/RunId/状态（Running, Paused, Succeeded, Failed, Cancelled）筛选的历史列表。
- **中栏 (Execution Timeline & Tool Ledger)**：
  - **动态 Step 瀑布流**：展示从 `Start` -> `Model Decision` -> `Tool Executing` -> `Approval Paused` -> `Completed` 的完整生命周期。
  - **Step 详情弹窗**：展示单步 Token 消耗（Prompt Cache 命中数、内部 Reasoning Token 数）、耗时、Context 预算分配。
  - **Tool Ledger**：展开工具调用的名称、风险级别、Guardrail 校验判定、输入/输出脱敏摘要。
- **右栏 (Human-in-the-loop 审批与 SSE 沙盒)**：
  - **审批控制面板**：当 Run 状态为 `Paused` 时，高亮展示等待审批的写工具操作（如 `execute_sql`），提供“批准”与“拒绝（带原因）”交互按钮，直接提交 `/api/v1/commands`。
  - **SSE Live Debugger**：提供交互式 Prompt 输入框，实时建立 SSE 连接，逐字流式渲染模型 Token Delta 与 `AgentEvent` 审计日志。

---

### 5.2 🧠 看板二：RAG 结构化谱系与 PDF 标注面板 (RAG & Lineage Inspector)

#### 布局架构
解决 RAG 调试中“无法追溯原文、无法验证切分与 ACL”的痛点：
- **顶部索引切换器**：选择 `zyblw_agent_knowledge` Schema 下的 Active 向量索引版本（如 `vector(1536)`），展示文档总数、Chunk 总数、Staging 暂存状态。
- **双栏 PDF 谱系对照区**：
  - **左栏 (PDF 原文几何渲染)**：使用 `pdfjs-dist` 渲染 PDF 页面，根据 `DocumentBoundingBox` 坐标数据，在 Canvas 上动态叠加半透明彩色矩形高亮框（BBox）。
  - **右栏 (结构 Block 树与 Lineage)**：展示 `DocumentStructureChunker` 解析出的 `DocumentBlock` 节点树（标题级别、段落、表格、列表项），以及每个 Chunk 的阅读顺序 (`ordinal`, `previousChunkId`/`nextChunkId`) 和父级 ID (`parentId`)。
- **Hybrid 检索测试沙盒 (Retrieval Sandbox)**：
  - **检索模拟器**：输入测试 Query，选择指定 `TenantId` 与 `permissions` 集合。
  - **多阶段信号拆解**：可视化展示 Vector Cosine Score、PostgreSQL FTS Score、Weighted RRF 组合得分、Reranker 重排得分以及上下文扩展（Neighbor Radius 与 Parent Siblings）的演变过程。

---

### 5.3 ⚙️ 看板三：分布式 Worker 与 Command 队列监控 (Queue & Worker Ops)

#### 布局架构
- **Worker 节点集群看板**：实时展示活动 `WorkerHost` 列表、节点 IP、`WorkerId`、活动 Lease 数量、最后心跳时间与 Fencing Generation 计数器。
- **Command 队列深度指标 (Queue Snapshot Gauges)**：
  - `queuedCommands`: 待消费命令数
  - `dispatchableRuns`: 可调度 Run 数
  - `leasedRuns`: 正在执行的 Run 数
  - `expiredLeases`: 过期租约数
  - `deadLetterCommands`: 死信命令数
- **死信与过期租约救援工具**：允许运维人员针对超时的过期租约执行显式 Reclaim，或查看死信命令的错误分类。

---

### 5.4 📈 看板四：评测趋势与发布门禁大盘 (Eval Trends & Release Gate Analytics)

#### 布局架构
- **发布门禁状态 (Release Gate Status)**：展示最近 CI/CD 构建的 Fail-Closed 门禁判定结果。
- **质量指标趋势图 (Metrics Trend Curves)**：
  - `pass@k` (至少一次成功率) 历史折线图
  - `pass^k` (连续全成功率) 历史折线图
  - 细分维度得分：引用准确度 (Citation Correctness)、工具选择准确度 (Tool Selection)、拒答率。
- **Token 消耗与成本预估 (Cost & Token Telemetry)**：按 Agent/模型维度展示累计 Prompt Cache 命中率、推理 Token 占比及预估花费 (USD)。

---

## 6. API 交互契约映射

控制台完全建立在 `zyblw-agent` 现有 REST/SSE 接口之上，无需破坏后端架构：

| 看板功能 | 后端 API 端点 | 传输协议 |
|---|---|---|
| Run 追溯与 Timeline | `GET /api/v1/runs/{runId}/inspection` | REST JSON |
| 实时事件流调试 | `GET /api/v1/runs/{runId}/events/stream` | SSE (Server-Sent Events) |
| 提交 Run / 审批 Command | `POST /api/v1/agents/{agentId}/runs`, `POST /api/v1/commands` | REST JSON |
| Worker 队列快照 | `GET /api/v1/admin/queue/snapshot` | REST JSON |
| RAG 索引与谱系信息 | `GET /api/v1/admin/rag/indices` | REST JSON |
| API 规范与结构 | `/api/v1/openapi.json` | REST JSON |

---

## 7. 实施路线图 (Implementation Phases)

1. **Phase 1 (架构与项目骨架)**: 在独立目录或 submodule 初始化 Next.js 15 + Tailwind CSS v4 骨架，定义 TypeScript API DTO。
2. **Phase 2 (Run 调试与审批 UI)**: 完成 Run Timeline 视图、Tool Ledger、审批控制与 SSE 调试器。
3. **Phase 3 (RAG 谱系与 PDF 渲染)**: 集成 `pdfjs-dist`，实现 Bounding Box 叠加与结构 Block 树对照查看器。
4. **Phase 4 (Worker 监控与 Eval 大盘)**: 接入队列快照 API 与评测趋势图表，完成 SSG 嵌入式打包。
