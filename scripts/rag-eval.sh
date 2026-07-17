#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CASES_DIR="${CASES_DIR:-docs/rag-eval-corpus/cases}"
CASE_IDS_FILE=""
OUTPUT_FILE=""
TOP_K="${TOP_K:-5}"
MAX_TOKENS="${MAX_TOKENS:-1024}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-180}"
THRESHOLD_LABEL="${RAG_SIMILARITY_THRESHOLD:-unknown}"
DRY_RUN=false

usage() {
  cat <<'EOF'
用法：
  scripts/rag-eval.sh [选项]

选项：
  --base-url URL       后端地址，默认 http://localhost:8080
  --cases-dir DIR      JSONL 题库目录，默认 docs/rag-eval-corpus/cases
  --case-ids FILE      只执行文件中列出的题号，空行和 # 注释会被忽略
  --output FILE        结果 JSONL 路径；默认写入 results 下的时间戳文件
  --top-k NUMBER       固定 topK，默认 5
  --max-tokens NUMBER  固定 maxTokens，默认 1024
  --timeout SECONDS    单题超时，默认 180 秒
  --threshold LABEL    仅记录本轮后端阈值标签，不会修改运行中的后端
  --dry-run            只校验题库并输出将执行的题目数量
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --cases-dir) CASES_DIR="$2"; shift 2 ;;
    --case-ids) CASE_IDS_FILE="$2"; shift 2 ;;
    --output) OUTPUT_FILE="$2"; shift 2 ;;
    --top-k) TOP_K="$2"; shift 2 ;;
    --max-tokens) MAX_TOKENS="$2"; shift 2 ;;
    --timeout) REQUEST_TIMEOUT="$2"; shift 2 ;;
    --threshold) THRESHOLD_LABEL="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

if ! command -v jq >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
  echo "评测脚本需要 jq 和 curl。" >&2
  exit 2
fi
if [[ ! -d "$CASES_DIR" ]]; then
  echo "题库目录不存在：$CASES_DIR" >&2
  exit 2
fi
if [[ -n "$CASE_IDS_FILE" && ! -f "$CASE_IDS_FILE" ]]; then
  echo "题号文件不存在：$CASE_IDS_FILE" >&2
  exit 2
fi

mapfile_compat() {
  # macOS 自带 Bash 3.2 没有 mapfile，因此使用 while 兼容读取。
  CASE_FILES=()
  while IFS= read -r file; do
    CASE_FILES+=("$file")
  done < <(find "$CASES_DIR" -maxdepth 1 -type f -name '*.jsonl' | sort)
}

case_selected() {
  local qid="$1"
  if [[ -z "$CASE_IDS_FILE" ]]; then
    return 0
  fi
  awk 'NF && $1 !~ /^#/ { print $1 }' "$CASE_IDS_FILE" | grep -Fxq "$qid"
}

parse_sse_answer() {
  local body_file="$1"
  # 当前 v1 接口返回原始文本增量；去掉 SSE 的 data: 前缀后按原顺序拼接。
  awk '
    /^data:/ {
      sub(/^data:[ ]?/, "")
      sub(/\r$/, "")
      printf "%s", $0
    }
  ' "$body_file"
}

count_citations() {
  local answer="$1"
  local matches
  matches="$(printf '%s' "$answer" | grep -Eo '\[[0-9]+#[0-9]+\]' || true)"
  if [[ -z "$matches" ]]; then
    printf '0'
  else
    printf '%s\n' "$matches" | sort -u | wc -l | tr -d ' '
  fi
}

count_foreign_citations() {
  local answer="$1"
  local post_id="$2"
  local foreign=0
  local citation
  while IFS= read -r citation; do
    [[ -z "$citation" ]] && continue
    if [[ "$citation" != "[$post_id#"* ]]; then
      foreign=$((foreign + 1))
    fi
  done < <(printf '%s' "$answer" | grep -Eo '\[[0-9]+#[0-9]+\]' | sort -u || true)
  printf '%s' "$foreign"
}

mapfile_compat
if [[ ${#CASE_FILES[@]} -eq 0 ]]; then
  echo "题库目录中没有 JSONL 文件：$CASES_DIR" >&2
  exit 2
fi

selected_count=0
for case_file in "${CASE_FILES[@]}"; do
  while IFS= read -r case_json || [[ -n "$case_json" ]]; do
    [[ -z "$case_json" ]] && continue
    qid="$(jq -er '.qid' <<<"$case_json")"
    if case_selected "$qid"; then
      selected_count=$((selected_count + 1))
    fi
  done < "$case_file"
done

if [[ "$DRY_RUN" == true ]]; then
  echo "题库文件：${#CASE_FILES[@]} 个"
  echo "待执行题目：$selected_count 道"
  echo "固定参数：topK=$TOP_K, maxTokens=$MAX_TOKENS, threshold=$THRESHOLD_LABEL"
  exit 0
fi
if [[ "$selected_count" -eq 0 ]]; then
  echo "没有匹配到待执行题目。" >&2
  exit 2
fi

run_id="$(date '+%Y%m%d-%H%M%S')"
if [[ -z "$OUTPUT_FILE" ]]; then
  OUTPUT_FILE="docs/rag-eval-corpus/results/eval-${run_id}-threshold-${THRESHOLD_LABEL}.jsonl"
fi
mkdir -p "$(dirname "$OUTPUT_FILE")"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

completed=0
for case_file in "${CASE_FILES[@]}"; do
  while IFS= read -r case_json || [[ -n "$case_json" ]]; do
    [[ -z "$case_json" ]] && continue
    qid="$(jq -er '.qid' <<<"$case_json")"
    case_selected "$qid" || continue

    post_id="$(jq -er '.postId' <<<"$case_json")"
    corpus="$(jq -er '.corpus' <<<"$case_json")"
    type="$(jq -er '.type' <<<"$case_json")"
    question="$(jq -er '.question' <<<"$case_json")"
    expected="$(jq -er '.expected' <<<"$case_json")"
    request_id="eval-${run_id}-${qid}"
    body_file="$temp_dir/${qid}.sse"

    echo "[$((completed + 1))/$selected_count] $qid $question"
    set +e
    curl_metrics="$(
      curl --silent --show-error --no-buffer --get \
        --max-time "$REQUEST_TIMEOUT" \
        --header "Accept: text/event-stream" \
        --header "X-Request-ID: $request_id" \
        --data-urlencode "question=$question" \
        --data-urlencode "topK=$TOP_K" \
        --data-urlencode "maxTokens=$MAX_TOKENS" \
        --output "$body_file" \
        --write-out $'%{http_code}\t%{time_total}' \
        "$BASE_URL/api/v1/knowposts/$post_id/qa/stream"
    )"
    curl_exit=$?
    set -e

    IFS=$'\t' read -r http_code total_seconds <<<"$curl_metrics"
    answer="$(parse_sse_answer "$body_file")"
    citation_count="$(count_citations "$answer")"
    foreign_citation_count="$(count_foreign_citations "$answer" "$post_id")"
    refusal_detected=false
    service_unavailable=false
    [[ "$answer" == "当前知文中没有足够信息回答这个问题。" ]] && refusal_detected=true
    [[ "$answer" == "AI问答服务暂时不可用，请稍后重试。" ]] && service_unavailable=true

    behavior_pass=false
    if [[ "$service_unavailable" == true ]]; then
      # 基础设施或模型服务异常不能算作业务拒答，也不能算作可回答题通过。
      behavior_pass=false
    elif [[ "$type" == "拒答" ]]; then
      [[ "$refusal_detected" == true ]] && behavior_pass=true
    elif [[ "$curl_exit" -eq 0 && "$http_code" == "200" && -n "$answer" && "$refusal_detected" == false ]]; then
      behavior_pass=true
    fi

    status="success"
    if [[ "$curl_exit" -ne 0 ]]; then
      status="curl_error"
    elif [[ "$http_code" != "200" ]]; then
      status="http_error"
    elif [[ "$service_unavailable" == true ]]; then
      status="service_unavailable"
    fi

    jq -cn \
      --arg runId "$run_id" \
      --arg threshold "$THRESHOLD_LABEL" \
      --arg qid "$qid" \
      --arg corpus "$corpus" \
      --arg postId "$post_id" \
      --arg type "$type" \
      --arg question "$question" \
      --arg expected "$expected" \
      --arg answer "$answer" \
      --arg requestId "$request_id" \
      --arg status "$status" \
      --arg httpCode "$http_code" \
      --arg totalSeconds "$total_seconds" \
      --argjson curlExit "$curl_exit" \
      --argjson topK "$TOP_K" \
      --argjson maxTokens "$MAX_TOKENS" \
      --argjson citationCount "$citation_count" \
      --argjson foreignCitationCount "$foreign_citation_count" \
      --argjson refusalDetected "$refusal_detected" \
      --argjson serviceUnavailable "$service_unavailable" \
      --argjson behaviorPass "$behavior_pass" \
      '{runId:$runId,threshold:$threshold,qid:$qid,corpus:$corpus,postId:$postId,type:$type,
        question:$question,expected:$expected,answer:$answer,requestId:$requestId,status:$status,
        httpCode:$httpCode,curlExit:$curlExit,totalSeconds:($totalSeconds|tonumber?),
        topK:$topK,maxTokens:$maxTokens,citationCount:$citationCount,
        foreignCitationCount:$foreignCitationCount,refusalDetected:$refusalDetected,
        serviceUnavailable:$serviceUnavailable,behaviorPass:$behaviorPass}' >> "$OUTPUT_FILE"

    completed=$((completed + 1))
  done < "$case_file"
done

echo "评测完成：$completed 道"
echo "结果文件：$OUTPUT_FILE"
