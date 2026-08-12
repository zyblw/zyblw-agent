import type { NextConfig } from "next";

const basePath = process.env.NEXT_PUBLIC_AGENT_BASE_PATH ?? "";

const nextConfig: NextConfig = {
  /**
   * `standalone` 输出让容器镜像只包含实际用到的依赖，而不是整个 `node_modules`。
   * 控制台是运维工具，镜像越小越容易在受限环境里分发。
   */
  output: "standalone",
  basePath,

  /**
   * 控制台只在浏览器里直接调用后端管理 API，不做服务端数据获取，因此 React 严格模式下的双次渲染
   * 不会产生重复请求；开启它可以更早暴露副作用写在渲染里的问题。
   */
  reactStrictMode: true,
};

export default nextConfig;
