# 测试与验证的信号

用于新增或修改测试；oracle 来自需求、旧合同和独立事实，不照抄实现的计算过程。

## 选择本次必要的检查

- 纯逻辑：边界、非法状态、预算/排序/数值不变量；已有测试足以证明机械改动时不添重复用例。
- 资源/并发：取消清理、失败路径、所有权与 CAS/lease 冲突。
- 权限/存储：真实 SQL 过滤、事务回滚、撤回及版本隔离，使用仓库的临时 PostgreSQL 契约入口。
- 外部协议：现有 stub/fixture 校验边界；真实 Provider、生产数据或收费评测不自动启用。
- 文档/skill：检查 frontmatter、引用路径、示例依据与触发场景；不为改变说明文字启动整套后端。

## 时间与观察

TestClock 用于虚拟推进时间；ZIO.sleep 在测试时可能由测试时钟控制，不等同于 Thread.sleep 阻塞线程。
推进时钟后，用 join、Promise、Queue 或明确可观察状态等待结果；不要仅凭“已经 adjust”就立即断言后台写入完成。
必须接触真实 I/O 的测试用有界超时与明确握手，不用任意真实等待碰运气。
参见 [ZIO TestClock](https://zio.dev/reference/test/services/clock/)。

同一实现者可以写代码与测试，但预期值/拒绝条件必须独立于实现；必要时使用旧版本数据、协议 fixture、性质或人工 gold。是否独立审核按风险与已有授权决定。

## 命令与报告

从目标仓库 AGENTS/build 中选择模块 ID 与命令。框架合并/发布门禁可能使用 testFull；平台 app 有自己的任务，不把 postgres/testFull 机械复制到所有仓库。
Testcontainers 未启用或测试被忽略时报告 conditional/ignored，不算通过。报告命令、结果和适用范围；测试数和编译通过都不是实际模型质量或临床正确率。
