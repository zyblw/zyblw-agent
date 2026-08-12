# zyblw-agent 控制台

`zyblw-agent` 框架的运维控制台。它是一个纯浏览器端的 Next.js 应用，全部数据来自后端的
`/api/v1/admin/**`，自身不持有任何状态、不做服务端数据获取、也不直连数据库。

控制台**不是**框架的必需组件。后端不装配管理路由时它完全无法工作，这是有意的：管理面能读取跨租户聚合
并改变部署行为，因此必须由宿主显式启用并授权。

## 功能

| 页签 | 内容 | 依赖的后端能力 |
| --- | --- | --- |
| 运行 | Run 目录、状态聚合、keyset 翻页、Langfuse trace 深链 | `runDirectory` |
| 知识库 | 索引清单（游标翻页）、检索沙盒、异步文档摄入 | `knowledge` |
| 队列 | 积压快照、死信清单与人工重排 | `queueOps` |
| 模型 | 已注册模型目录、运行时切换、连通性探活、Embedding 只读说明 | `models`（切换还需 `runtimeConfig`） |
| 配置 | 基线/覆盖/生效三列对照、乐观锁写入、审计历史 | `runtimeConfig` |
| 安全 | 工具治理、审批强度、待审批积压、授权模型 | `runtimeConfig` |
| 评测 | 通过率走势、维度分数与发布门禁 | `evalTrends` |

页签可见性由 `GET /api/v1/admin/capabilities` 决定。后端未装配的能力对应的页签不会显示，而不是显示一个
只会返回 404 的空面板。未填写凭据时不渲染任何面板：每一个都会立刻 401，而真正的原因会被埋在一片红色错误
横幅里。

## 模型治理

模型页把「有哪些模型可用」「现在跑的是哪个」「切过去会不会失败」放在同一屏，并提供三件事：

- **目录**。按 Provider 分组列出全部已注册组合，展示能力位（工具调用、并行工具、严格 Schema、视觉、思考、
  流式）、上下文窗口、单价与凭据状态。`declaredModel` 为 false 的行会标注它只是该 Provider 的部署默认模型，
  能力是按 Provider 级推断出来的。
- **切换**。Provider 与模型只能从目录里选，不接受自由输入：后端会拒绝未注册的组合（400
  `InvalidConfiguration`），但在被拒绝之前，一个拼错的 Provider 名已经让人以为自己配对了。
- **探活**。`POST /api/v1/admin/models/probe` 向 Provider 发一次真实调用并产生真实费用，因此需要
  `agent:admin:debug`，且只在点击时发起。它只返回成功与否、耗时和 token 用量，**不返回模型输出正文**——否则
  这个端点就变成了一个可以向任意 Provider 提问并读回答案的通道。

**切换走的是配置覆盖写入路径**，不是一个新的写端点：模型工作点与工具治理共用同一套乐观锁、审计历史和跨副本
刷新，为模型再造一条写入路径会产生两份可能互相矛盾的配置事实。因此模型页提交的是一份**完整的覆盖快照**，
其中原样带上别人已经设置的工具白名单等项；配置页则把这四项显示为只读并指回模型页，让每一项配置只有一个
写入口。

### 凭据是引用式的

`ModelCredentialStatus` 只有 `present`（装配时是否解析到非空凭据）与 `reference`（例如
`env:DEEPSEEK_API_KEY`）两个字段。管理台需要回答的问题是「切到这个 Provider 会不会因为缺凭据而全线失败」，
回答它不需要看到 Key。因此控制台的任何位置都**不展示、不请求、也不存储 API Key**，缺凭据的行只会给出应该去
配置哪个引用。

### Embedding 不能在运行时切换

向量化模型是只读的，页面上没有任何切换入口，`immutableReason` 由后端下发并原样展示。原因有两层：向量维度被
Flyway 迁移固定，而一份索引里的向量只能与生成它的模型比较——换模型等于让整个知识库的既有向量失去意义。一个
能保存成功却悄悄让 RAG 召回质量崩塌的开关，比没有开关危险得多。真正需要换模型的部署必须走「新维度迁移 +
全量重新摄入」的运维流程。模型输出维度与索引列维度不一致时页面会显著告警，因为此时任何摄入都会在写入前失败。

## 深链

界面状态放在 URL query 里，刷新与分享后可恢复：

| 参数 | 含义 |
| --- | --- |
| `tab` | 当前页签 |
| `runTenant` / `runAgent` / `runStatus` / `runAwaiting` / `runId` | Run 页的租户、Agent、状态集合（逗号分隔）、仅待审批（`1`）、选中的 Run |
| `ragTenant` | 知识库页的租户；索引清单、检索沙盒与摄入共用它 |
| `suite` | 评测页选中的趋势线（`kind/suiteId/datasetId/datasetVersion`） |
| `modelProvider` / `modelName` | 模型页选中的组合 |

keyset 游标刻意不进 URL：它是只对某一组过滤条件有效的不透明令牌，分享一个带游标的地址会让对方从一个无法
解释的位置开始看。参数写入一律用 `router.replace`，因为勾选一个状态过滤不该在浏览器历史里留下一条记录。

自由文本筛选（`runTenant`、`runAgent`、`ragTenant`）经过 300 毫秒防抖，因此地址栏会比输入框慢一步。直写会
让一个 12 字符的租户 ID 产生 12 个不同的 query key，也就是 12 次跨租户目录扫描。这里没有改成回车/失焦提交：
那需要一份可以与 URL 长期分叉的本地状态，而"URL 是唯一事实来源"是整个深链设计的前提。防抖两者都不牺牲，
代价只是地址栏晚 300 毫秒定稿——没有人会在两次击键之间分享链接。

## 授权

管理接口一律要求显式 scope，缺失即拒绝。管理面看到的是整个部署而不是单个 Run 的所有者视角，因此不能复用
业务侧「归属即可读」的规则。

| scope | 用途 |
| --- | --- |
| `agent:admin:read` | 只读聚合：Run 目录、队列积压、有效配置快照、模型目录、评测趋势 |
| `agent:admin:write` | 改变部署行为：工具白名单、审批策略、模型切换、死信重排、索引退役（蕴含读权限） |
| `agent:admin:debug` | 产生真实 Provider 费用：检索沙盒、文档摄入、模型探活（不被写权限蕴含） |

框架不自带认证中间件。身份由宿主的 `AgentRequestContextResolver` 解析。独立模式下控制台透传 Bearer token；
同域嵌入模式下，宿主 BFF 使用 HttpOnly Cookie 完成认证，控制台 JavaScript 不读取 JWT。

地址保存在 `localStorage`，token 只保存在 `sessionStorage`：管理 token 能改工具白名单和审批策略，让它在
关闭标签页后继续留在磁盘上没有必要的收益。

## 本地开发

```bash
npm ci
cp .env.example .env.local     # 可选：修改默认后端地址
npm run dev
```

打开 <http://localhost:3000>，在右上角填写后端地址与 token。

后端与控制台不同源时，宿主需要允许控制台来源的 CORS 预检，否则浏览器会在 `capabilities` 探测阶段就失败。

## 校验

```bash
npm run typecheck   # next typegen + tsc --noEmit
npm run lint        # ESLint
npm run build       # 生产构建
npm run test:e2e:install  # 首次安装 Chromium
npm run test:e2e    # 浏览器契约：凭据门禁、模型目录、键盘操作、探活与脱敏
npm run test:e2e:host # 同域宿主会话、BFF 路径、无 Authorization 与 CSRF 契约
```

类型检查必须走 `npm run typecheck` 而不是直接 `tsc --noEmit`。`LayoutProps` 等路由感知类型由 Next.js 生成到
`.next/types`，裸跑 `tsc` 只在已经构建过的机器上通过，在干净检出上会失败。`next typegen` 不做完整构建就生成这些
类型，因此类型检查在 CI 上仍然是一道真实门禁。

浏览器测试拦截 `/api/v1/admin/**` 并返回确定性契约响应，不读取真实环境变量、不访问真实 Provider，也不会产生费用。
它验证的是控制台与管理 API wire shape 的集成；真实 Provider 的 TLS、凭据和在线协议仍由 opt-in live smoke 负责。

## 部署

```bash
docker build -t zyblw-agent-dashboard \
  --build-arg NEXT_PUBLIC_AGENT_BASE_URL=https://agent.example.com .
docker run -p 3000:3000 zyblw-agent-dashboard
```

`NEXT_PUBLIC_AGENT_BASE_URL` 只是**默认值**，运行时仍可在界面上切换。同一份镜像可以用于多个环境，不必为
每个环境重新构建。

### 嵌入宿主站点

```bash
docker build -t zyblw-agent-dashboard \
  --build-arg NEXT_PUBLIC_AGENT_BASE_URL=/api/backend \
  --build-arg NEXT_PUBLIC_AGENT_AUTH_MODE=host-session \
  --build-arg NEXT_PUBLIC_AGENT_BASE_PATH=/admin/agent .
```

宿主必须把 `/admin/agent/**` 反向代理到该容器，并让 `/api/backend/**` 以同源 HttpOnly 会话转发到 Agent 管理
API。`host-session` 模式自动携带同源 Cookie 与 `X-ZYBLW-CSRF: 1`，界面隐藏连接/token 输入；401 和 403 仍由
宿主认证与 `AgentRequestContextResolver` 决定。

Langfuse、Grafana 和 OTLP 端点刻意不由前端配置，而是后端通过 `capabilities` 下发（对应
`ZYBLW_AGENT_OBSERVABILITY_*` 环境变量）。这样一次后端部署配置就能同时纠正所有页面的跳转目标。

## 后端装配

控制台需要宿主把管理路由合并进 HTTP 应用，并按需提供各能力的适配器。未提供的能力不会挂载路由，请求自然
得到 404，`capabilities` 会如实报告它不可用。装配细节见 `docs/` 下的管理 API 文档。

## 设计约束

- **线格式即类型**。`src/types/admin.ts` 与 Scala 侧的 case class 一一对应，字段名和可空性完全一致。
  这里刻意不做「前端更好用」的改名：一旦线格式与视图模型分叉，后端改字段时 TypeScript 就再也发现不了问题。
- **不展示业务正文**。Run 列表只含元数据；用户输入、模型输出和工具参数属于业务数据，跨租户的运维界面不
  应成为它们的导出通道。模型探活同理，只报告成败与用量。
- **不接触凭据**。控制台只透传宿主签发的 Bearer token，从不展示、请求或存储 Provider 的 API Key。
- **费用可见**。检索沙盒、文档摄入和模型探活会调用 Provider 并产生真实费用，因此它们只在点击时发起请求，
  不因窗口重新聚焦或组件重挂载而自动重发，按钮上也如实标注这一点。
- **每项配置只有一个写入口**。同一项配置若存在两个可写界面，两处的校验逻辑会逐渐分叉。因此工具治理只在
  配置页可改，模型工作点只在模型页可改，安全页始终只读。
- **写操作必有回执**。配置保存、死信重排、索引退役、摄入提交、模型切换与探活都会给出一条通知；这些操作
  大多没有立竿见影的界面变化，没有回执就无法区分「改成功了」和「按钮没响应」。
- **如实标注生效边界**。配置页每一项都标注「立即 / 下个 Run / 需重启」。一个保存成功却要等重启才起作用的
  开关，如果不明确说明，会让运维误以为限制已经收紧。
