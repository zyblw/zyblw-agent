package com.zyblw.agent.core

import zio.Config

/** 为 ZIO 默认环境变量 Provider 构造 shell-safe 的点分配置路径。
  *
  * ZIO 2.1 的默认环境 Provider 会把 path segment 用下划线连接并转成大写，但不会把 segment 内部的连字符自动替换成 下划线。例如，单一 segment
  * `zyblw-agent` 会查找包含 `-` 的环境变量，而不是通常期望的 `ZYBLW_AGENT`。这类变量虽然 可以通过底层 `env` 命令注入，却不能被常规 shell `export`，也容易与
  * Docker/Kubernetes 配置约定不一致。
  *
  * 本工具规定：
  *
  *   - prefix 使用点号表达层级，例如 `zyblw.agent.http.host`；
  *   - 每个 segment 只允许字母、数字和下划线；
  *   - 叶子键同样应使用 `snake_case`，不要使用连字符；
  *   - 逐段调用 `nested`，不能把整个点分字符串当成一个 segment。
  *
  * 这样 `zyblw.agent.http.host` + `service_name` 会稳定映射为 `ZYBLW_AGENT_HTTP_HOST_SERVICE_NAME`。
  */
object ZioConfigPath:
  /** 把点分 prefix 逐段包裹到配置描述外层。
    *
    * @param config
    *   尚未添加应用根路径的 ZIO Config 描述
    * @param prefix
    *   点分路径，例如 `zyblw.agent`
    * @return
    *   具有真实多 segment 路径的配置描述
    * @throws IllegalArgumentException
    *   prefix 为空、包含空段或使用连字符/其他不安全字符
    */
  def nested[A](config: Config[A], prefix: String): Config[A] =
    val segments = prefix.split("\\.", -1).toList.map(_.trim)
    require(
      segments.nonEmpty &&
        segments.forall(segment => segment.nonEmpty && segment.matches("[A-Za-z0-9_]+")),
      "ZIO Config prefix 必须是由点分隔的字母、数字或下划线路径"
    )
    segments.foldRight(config)((segment, value) => value.nested(segment))
