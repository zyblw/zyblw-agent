#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cache_dir="${PUBLIC_PDF_CACHE_DIR:-${repo_dir}/target/public-pdf-corpus}"
fixture="${cache_dir}/docling-technical-report-2408.09869.pdf"
expected_sha="82dd470712ce8389f19f20eb9330475e2166a281f8c7990a9f1d0763d73b4d22"
source_url="https://arxiv.org/pdf/2408.09869"
open_rag_positive="${cache_dir}/open-rag-bench-2404.08757v2.pdf"
open_rag_positive_sha="82252bbfb0f00b47d1c9110f2004489b3ca8687031394b6f98c5278c14fe5888"
open_rag_positive_url="https://arxiv.org/pdf/2404.08757v2"
open_rag_negative="${cache_dir}/open-rag-bench-2407.01528v3.pdf"
open_rag_negative_sha="68eca45ee66f6c88de378e13ddae531e241f362a4db24ceecf5d34297928fd33"
open_rag_negative_url="https://arxiv.org/pdf/2407.01528v3"

mkdir -p "${cache_dir}"

download_verified() {
  local target="$1"
  local expected="$2"
  local url="$3"
  if [[ -f "${target}" ]]; then
    local cached_sha
    cached_sha="$(shasum -a 256 "${target}" | awk '{print $1}')"
    if [[ "${cached_sha}" != "${expected}" ]]; then
      echo "缓存 PDF 校验失败；请删除 ${target} 后重试。" >&2
      exit 1
    fi
    return
  fi

  partial="${target}.download.$$"
  trap 'rm -f "${partial:-}"' EXIT
  curl --fail --location --retry 3 --connect-timeout 20 \
    --output "${partial}" "${url}"
  actual_sha="$(shasum -a 256 "${partial}" | awk '{print $1}')"
  if [[ "${actual_sha}" != "${expected}" ]]; then
    echo "公开 PDF 校验失败：expected=${expected}, actual=${actual_sha}" >&2
    exit 1
  fi
  mv "${partial}" "${target}"
  trap - EXIT
}

download_verified "${fixture}" "${expected_sha}" "${source_url}"
download_verified "${open_rag_positive}" "${open_rag_positive_sha}" "${open_rag_positive_url}"
download_verified "${open_rag_negative}" "${open_rag_negative_sha}" "${open_rag_negative_url}"

scala_string() {
  local value="$1"
  if [[ "${value}" == *$'\n'* || "${value}" == *$'\r'* ]]; then
    echo "公开 PDF 缓存路径不能包含换行符。" >&2
    exit 1
  fi
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s' "${value}"
}

fixture_property="$(scala_string "${fixture}")"
positive_property="$(scala_string "${open_rag_positive}")"
negative_property="$(scala_string "${open_rag_negative}")"

cd "${repo_dir}"
sbt -batch \
  ";eval java.lang.System.setProperty(\"RUN_PUBLIC_PDF_INTEGRATION\", \"1\");eval java.lang.System.setProperty(\"PUBLIC_PDF_FIXTURE\", \"${fixture_property}\");eval java.lang.System.setProperty(\"OPEN_RAG_BENCH_POSITIVE_PDF\", \"${positive_property}\");eval java.lang.System.setProperty(\"OPEN_RAG_BENCH_NEGATIVE_PDF\", \"${negative_property}\");documentLoaders/testOnly com.zyblw.agent.loaders.PublicPdfRagIntegrationSpec"
