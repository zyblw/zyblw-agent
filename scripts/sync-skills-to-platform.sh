#!/usr/bin/env bash
# 将当前 zyblw-agent 的公共 AI 研发技能库 (scala3-zio2-stack) 同步到相邻的 zyblw-platform，
# 确保业务平台和后端服务能独立保持最新的工程方法论并纳入 Git 跟踪。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_DIR="${ZYBLW_PLATFORM_DIR:-$ROOT_DIR/../zyblw-platform}"

SOURCE_SKILL_DIR="$ROOT_DIR/.agents/skills/scala3-zio2-stack"
TARGET_SKILL_DIR="$PLATFORM_DIR/.agents/skills/scala3-zio2-stack"

if [[ ! -d "$SOURCE_SKILL_DIR" ]]; then
  echo "未找到源技能目录：$SOURCE_SKILL_DIR" >&2
  exit 2
fi

if [[ ! -d "$PLATFORM_DIR" ]]; then
  echo "未找到相邻 zyblw-platform 目录：$PLATFORM_DIR" >&2
  exit 2
fi

echo "Syncing scala3-zio2-stack from $SOURCE_SKILL_DIR to $TARGET_SKILL_DIR ..."
mkdir -p "$TARGET_SKILL_DIR"
rsync -av --delete "$SOURCE_SKILL_DIR/" "$TARGET_SKILL_DIR/"

echo "Successfully synchronized scala3-zio2-stack to zyblw-platform."
