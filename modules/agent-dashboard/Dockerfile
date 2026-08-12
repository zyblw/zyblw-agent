# zyblw-agent 控制台镜像。
#
# 多阶段构建：依赖安装、生产构建和运行时分离，使最终镜像不含构建工具链和开发依赖。
# 控制台是纯浏览器端应用，运行时只需要托管静态资源和 Next.js 的最小服务器。

FROM node:22-alpine AS deps
WORKDIR /app
# 先只复制清单文件，让依赖层在源码变化时仍可复用缓存。
COPY package.json package-lock.json ./
# 用 npm ci 而不是 npm install：它严格遵循 lockfile，保证镜像与本地验证过的依赖树一致。
RUN npm ci

FROM node:22-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# 默认后端地址可在构建时覆盖，但运行时仍可在界面上切换，因此多环境部署不必为此重新构建。
ARG NEXT_PUBLIC_AGENT_BASE_URL=http://localhost:8080
ARG NEXT_PUBLIC_AGENT_AUTH_MODE=bearer
ARG NEXT_PUBLIC_AGENT_BASE_PATH=
ENV NEXT_PUBLIC_AGENT_BASE_URL=$NEXT_PUBLIC_AGENT_BASE_URL
ENV NEXT_PUBLIC_AGENT_AUTH_MODE=$NEXT_PUBLIC_AGENT_AUTH_MODE
ENV NEXT_PUBLIC_AGENT_BASE_PATH=$NEXT_PUBLIC_AGENT_BASE_PATH
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# 以非 root 运行。控制台会在浏览器里持有管理凭据，容器本身没有任何需要特权的操作。
RUN addgroup -g 1001 -S nodejs && adduser -S -u 1001 -G nodejs nextjs

# standalone 输出已包含裁剪后的 node_modules 与最小服务器。
# 控制台没有 public/ 目录：favicon 由 App Router 从 src/app 提供。加入静态资源的部署需要补一行
# `COPY --from=builder --chown=nextjs:nodejs /app/public ./public`。
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
