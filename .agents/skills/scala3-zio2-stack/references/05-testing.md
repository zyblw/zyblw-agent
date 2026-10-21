# 05. 测试与验证的信号

用于编写或重构单元测试与集成测试。测试的核心价值在于提供可信的独立预言机（Independent Oracle），拒绝形式主义测试。

## 一、 独立测试预言机 (Independent Test Oracle)

预言机用于定义“系统在给定输入下的预期结果是什么”，且其定义来源必须**完全独立于具体的实现代码**：

- **严禁的反模式**：直接把业务实现的计算逻辑、私有转换函数复制粘贴到测试用例的 `assertTrue(actual == expected)` 中。这不仅无法捕获实现中的逻辑错误，反而会导致错误逻辑被测试“合法化”。
- **预言机的真正来源**：
  1. 真实需求规格与不变性性质（例如：无论输入何种序列，返回结果的排序必须单调递增，且长度与输入一致）；
  2. 预先冻结的 Gold 标准数据集或真实物理世界样例；
  3. 历史版本的已知输入输出契约快照（保证向后兼容性）。

## 二、 虚拟时钟与确定性异步同步 (TestClock)

- **告别脆弱的 Thread.sleep**：严禁在单元测试中编写 `Thread.sleep(...)` 或依赖真实物理时钟延迟的等待，这会导致测试偶发失败（Flaky Tests）并极大拖慢构建速度。
- **虚拟时钟推进**：在 ZIO Test 中使用 `TestClock.adjust(...)` 瞬时推进时间：
  ```scala
  for
    fiber <- backgroundWorker.fork
    _     <- TestClock.adjust(10.minutes)
    _     <- fiber.join
    state <- getState
  yield assertTrue(state.isTimedOut)
  ```
- **显式握手**：推进时钟后，必须通过 `Fiber.join`、`Promise.await` 或等待特定状态到达来确认后台操作已完成，严禁凭空假设时钟推进后副作用必定已经落盘。

## 三、 确定性 AI 桩与无带外网络调用 (Deterministic AI Testkit)

- **普通 PR 零调用真实 LLM**：日常开发、单测和普通 CI 构建中，**严禁向收费的真实大模型发起实时网络调用**。
- **使用确定性 Testkit**：必须统一使用 `agent-testkit` 提供的 Mock/Stub 模型（如 `FakeChatModel`、`RecordedResponseStub`），注入可预测的结构化响应或错误状态，实现离线、零成本、毫秒级测试。
- **评测隔离**：真实 Provider 的调用与端到端金标评估统一收拢到独立的 `evals` 模块中，仅在版本发布或定时基准任务中显式触发。

## 四、 变异抵抗思维 (Mutation Resistance Mindset)

在完成测试编写后，进行“变异推演（Mental Mutation Testing）”自检：
1. **反转条件**：如果把业务代码中的 `>` 改成 `>=`，或者把 `&&` 改成 `||`，是否有至少一个测试会失败？
2. **删除边界过滤**：如果把租户过滤条件或已撤回标记过滤从代码中删掉，测试套件是否能精准拦截？
3. **静默失败测试是负资产**：如果测试无论怎么改动核心逻辑都会通过，说明该测试是无效的代码膨胀，必须予以修正或剔除。

## 五、 架构边界测试 (Architecture Testing)

对于系统模块间的物理依赖方向，通过自动化测试进行机械性断言（参考 `ArchitectureSpec.scala`）：
- `domain` 核心层严禁导入 `api`、`adapter`、`platform`、`bootstrap`、`zio.http`、`java.sql` 或任何外部云 SDK。
- 一旦有新引入的穿透依赖，测试在本地构建期即刻失败，防止架构逐渐腐化。

## 六、 测试分类与执行速度

- **亚秒级纯内核测试**：领域层、状态机、数据转换必须实现纯内存单测，数百个用例需在 1–3 秒内极速完成。
- **集成测试隔离**：涉及真实 PostgreSQL、pgvector 或外部 Docker 容器的集成测试，统一隔离在专门的 integration 目录或受控测试配置中，在需要完整交付或 CI 阶段触发。
