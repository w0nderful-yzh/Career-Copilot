#!/usr/bin/env bash
# Career Copilot 一键启停脚本（Java 后端 / Python Agent / React 前端）
#
# 用法:
#   ./scripts/dev.sh start        启动三个服务
#   ./scripts/dev.sh stop         停止三个服务（按端口清理，避免残留）
#   ./scripts/dev.sh restart      重启
#   ./scripts/dev.sh status       查看各服务健康状态
#   ./scripts/dev.sh logs [svc]   查看日志（svc: java|agent|web，默认全部 tail -50）
#   ./scripts/dev.sh stop-port 8081   按端口停止任意服务（忘记关闭时兜底）
#
# 端口可通过环境变量覆盖:
#   SERVER_PORT=8081 AGENT_PORT=8001 WEB_PORT=5173 ./scripts/dev.sh start
#   （本机 8080/8000 被其他项目占用，默认使用 8081/8001）
#
# 根目录 .env 会自动加载（数据库 / 存储 / 模型密钥），**已导出的环境变量优先**：
# 上面的 SERVER_PORT 等显式传参不会被 .env 覆盖（与 docker compose 读取 .env 的语义一致）。

set -euo pipefail

# ===== 配置 =====
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 加载根目录 .env 到当前环境。
#
# 为什么需要它：Java 侧数据源默认密码是 123456，而 .env 里是 compose 实际使用的值；
# 不加载会导致 bootRun 连库失败（密码不匹配），排障成本高且不易与「数据库没起」区分。
#
# 语义：已存在的环境变量优先（printenv 能区分「已设为空」与「未设置」，
# 故显式传入的空值同样不会被 .env 覆盖）。
load_dotenv() {
  local file="$ROOT_DIR/.env"
  [ -f "$file" ] || return 0

  local line trimmed key value
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"                                  # 兼容 CRLF 换行
    trimmed="${line#"${line%%[![:space:]]*}"}"            # 去掉前导空白
    case "$trimmed" in ''|'#'*) continue ;; esac          # 跳过空行与注释
    trimmed="${trimmed#export }"
    key="${trimmed%%=*}"
    value="${trimmed#*=}"
    # 非法键名（含空格/中文的说明行）直接跳过，避免 export 报错中断脚本
    case "$key" in ''|*[!A-Za-z0-9_]*) continue ;; esac
    # .env 允许 KEY="value" / KEY='value'
    case "$value" in
      \"*\"|\'*\') value="${value:1:${#value}-2}" ;;
    esac
    if ! printenv "$key" >/dev/null 2>&1; then
      export "$key=$value"
    fi
  done < "$file"
}

load_dotenv

JAVA_PORT="${SERVER_PORT:-8081}"
AGENT_PORT="${AGENT_PORT:-8001}"
WEB_PORT="${WEB_PORT:-5173}"
LOG_DIR="$ROOT_DIR/.dev-logs"
JAVA_LOG="$LOG_DIR/java-${JAVA_PORT}.log"
AGENT_LOG="$LOG_DIR/agent-${AGENT_PORT}.log"
WEB_LOG="$LOG_DIR/web-${WEB_PORT}.log"
JAVA_PROCESS_FILE="$LOG_DIR/java-${JAVA_PORT}.job"
AGENT_PROCESS_FILE="$LOG_DIR/agent-${AGENT_PORT}.job"
WEB_PROCESS_FILE="$LOG_DIR/web-${WEB_PORT}.job"
JAVA_READY_TIMEOUT="${JAVA_READY_TIMEOUT:-90}"
AGENT_READY_TIMEOUT="${AGENT_READY_TIMEOUT:-30}"
WEB_READY_TIMEOUT="${WEB_READY_TIMEOUT:-30}"

mkdir -p "$LOG_DIR"

# ===== 工具函数 =====
log() { printf "[dev] %s\n" "$*"; }
error() { printf "[dev][ERROR] %s\n" "$*" >&2; }

# 按端口取 PID（端口被占时返回第一个 PID）
port_pid() { lsof -ti tcp:"$1" 2>/dev/null | head -1; }

port_busy() { [ -n "$(port_pid "$1")" ]; }

tracked_pid() {
  local file="$1" value label info pid
  [ -f "$file" ] || return 1
  value="$(head -1 "$file")"
  case "$value" in
    launchd:*)
      label="${value#launchd:}"
      info="$(launchctl print "gui/$(id -u)/$label" 2>/dev/null)" || return 1
      printf "%s\n" "$info" | grep -q "state = running" || return 1
      pid="$(printf "%s\n" "$info" | awk '/pid = / { print $3; exit }')"
      [ -n "$pid" ] || return 1
      printf "%s" "$pid"
      ;;
    pid:*)
      pid="${value#pid:}"
      [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
      printf "%s" "$pid"
      ;;
    *) return 1 ;;
  esac
}

start_detached() {
  local process_file="$1" label="$2" log_file="$3" work_dir="$4"
  shift 4
  : > "$log_file"
  if [ "$(uname -s)" = "Darwin" ] && command -v launchctl >/dev/null 2>&1; then
    local command_line
    printf -v command_line 'cd %q && exec' "$work_dir"
    local arg
    for arg in "$@"; do
      printf -v command_line '%s %q' "$command_line" "$arg"
    done
    # launchd 脱离当前调用 shell 的进程组；submit 适合本地开发期临时任务，
    # remove 后不会留下登录项或系统级配置。
    launchctl remove "$label" 2>/dev/null || true
    launchctl submit -l "$label" -o "$log_file" -e "$log_file" -- \
      /bin/bash -c "$command_line"
    printf "launchd:%s\n" "$label" > "$process_file"
    return 0
  fi

  (
    cd "$work_dir"
    # 非 macOS 回退：stdin 与 SIGHUP 都脱离调用 shell，进程记录用于未绑定端口时停止。
    nohup "$@" </dev/null >"$log_file" 2>&1 &
    printf "pid:%s\n" "$!" > "$process_file"
  )
}

stop_tracked_process() {
  local process_file="$1" value pid label
  [ -f "$process_file" ] || return 0
  value="$(head -1 "$process_file")"
  case "$value" in
    launchd:*)
      label="${value#launchd:}"
      launchctl remove "$label" 2>/dev/null || true
      ;;
    pid:*)
      pid="${value#pid:}"
      [ -z "$pid" ] || kill "$pid" 2>/dev/null || true
      ;;
  esac
  rm -f "$process_file"
}

wait_service_ready() {
  local name="$1" port="$2" path="$3" timeout="$4" process_file="$5" log_file="$6"
  local elapsed=0 pid=""
  while [ "$elapsed" -lt "$timeout" ]; do
    if curl -sf -m 2 -o /dev/null "http://127.0.0.1:$port$path" 2>/dev/null; then
      log "$name :$port 已就绪"
      return 0
    fi
    pid="$(tracked_pid "$process_file")" || true
    if [ -f "$process_file" ] && [ -z "$pid" ] && [ "$elapsed" -ge 3 ]; then
      error "$name 启动失败：托管进程未运行（日志见 $log_file）"
      return 1
    fi
    # 端口由非本脚本进程占用，且健康端点不匹配：这是端口冲突，不是「启动中」。
    if [ -z "$pid" ] && port_busy "$port"; then
      error "$name 启动失败：端口 $port 已被占用，但健康检查 $path 未通过"
      return 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  pid="$(tracked_pid "$process_file")" || true
  if [ -n "$pid" ]; then
    error "$name 未就绪：进程 $pid 仍在运行，但 ${timeout}s 内健康检查未通过"
  else
    error "$name 启动失败：${timeout}s 内未就绪，且启动进程已退出"
  fi
  return 1
}

# 按端口停止服务（无法区分归属，但端口即服务的约定足够可靠）
stop_port() {
  local pid
  pid="$(port_pid "$1")" || true
  if [ -z "$pid" ]; then
    log "端口 $1 无进程，无需停止"
    return 0
  fi
  log "停止端口 $1 (pid=$pid)"
  kill "$pid" 2>/dev/null || true
  # 等待退出，最多 10 秒
  for _ in $(seq 1 10); do
    if ! kill -0 "$pid" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  # 仍未退出则强杀
  if kill -0 "$pid" 2>/dev/null; then
    log "端口 $1 进程未退出，强制结束"
    kill -9 "$pid" 2>/dev/null || true
  fi
}

# ===== 子命令 =====

start_java() {
  if port_busy "$JAVA_PORT"; then
    log "Java 端口 $JAVA_PORT 已被占用，跳过启动（如需重启先执行 stop）"
    return 0
  fi
  log "启动 Java 后端 :${JAVA_PORT}（日志 ${JAVA_LOG}）"
  # 直接定位 sdkman 中版本最高的 JDK（避免 source sdkman-init.sh 在非交互 shell 挂起）
  local sdkman_java
  sdkman_java="$(ls -d "$HOME"/.sdkman/candidates/java/*/ 2>/dev/null | sort -V | tail -1)"
  if [ -n "$sdkman_java" ] && [ -x "$sdkman_java/bin/java" ]; then
    export JAVA_HOME="${sdkman_java%/}"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  start_detached "$JAVA_PROCESS_FILE" "com.careercopilot.dev.java.$JAVA_PORT" \
    "$JAVA_LOG" "$ROOT_DIR" \
    env JAVA_HOME="$JAVA_HOME" PATH="$PATH" SERVER_PORT="$JAVA_PORT" \
    ./gradlew :app:bootRun --no-daemon
}

start_python() {
  if port_busy "$AGENT_PORT"; then
    log "Agent 端口 $AGENT_PORT 已被占用，跳过启动（如需重启先执行 stop）"
    return 0
  fi
  if [ ! -f "$ROOT_DIR/agent-service/.venv/bin/uvicorn" ]; then
    error "agent-service/.venv 不存在，请先执行: cd agent-service && uv sync"
    exit 1
  fi
  log "启动 Python Agent :${AGENT_PORT}（日志 ${AGENT_LOG}）"
  # 环境变量优先于 .env，确保指向当前 Java 端口
  start_detached "$AGENT_PROCESS_FILE" "com.careercopilot.dev.agent.$AGENT_PORT" \
    "$AGENT_LOG" "$ROOT_DIR/agent-service" \
    env PATH="$PATH" BACKEND_BASE_URL="http://127.0.0.1:$JAVA_PORT" \
    "$ROOT_DIR/agent-service/.venv/bin/uvicorn" career_copilot.main:app \
    --reload --port "$AGENT_PORT"
}

start_web() {
  if port_busy "$WEB_PORT"; then
    log "前端端口 $WEB_PORT 已被占用，跳过启动（如需重启先执行 stop）"
    return 0
  fi
  log "启动 React 前端 :${WEB_PORT}（日志 ${WEB_LOG}）"
  local pnpm_bin
  pnpm_bin="$(command -v pnpm)"
  # 环境变量覆盖 .env.development，确保代理指向当前端口
  start_detached "$WEB_PROCESS_FILE" "com.careercopilot.dev.web.$WEB_PORT" \
    "$WEB_LOG" "$ROOT_DIR/frontend" \
    env PATH="$PATH" VITE_API_PROXY_TARGET="http://127.0.0.1:$JAVA_PORT" \
    VITE_AGENT_PROXY_TARGET="http://127.0.0.1:$AGENT_PORT" \
    "$pnpm_bin" dev --port "$WEB_PORT"
}

cmd_start() {
  start_java
  # 先等 Java 就绪再启动 Agent：Agent 启动时会从 Java 同步 Agent 模型配置，
  # Java 未就绪会导致同步失败（虽有惰性重试兜底，但首次请求前多一次告警与重试）。
  log "等待 Java 就绪（最长 ${JAVA_READY_TIMEOUT}s）..."
  wait_service_ready "Java" "$JAVA_PORT" "/api/agent/tools" \
    "$JAVA_READY_TIMEOUT" "$JAVA_PROCESS_FILE" "$JAVA_LOG" || return 1
  start_python
  log "等待 Agent 就绪（最长 ${AGENT_READY_TIMEOUT}s）..."
  wait_service_ready "Agent" "$AGENT_PORT" "/health" \
    "$AGENT_READY_TIMEOUT" "$AGENT_PROCESS_FILE" "$AGENT_LOG" || return 1
  start_web
  log "等待 Web 就绪（最长 ${WEB_READY_TIMEOUT}s）..."
  wait_service_ready "Web" "$WEB_PORT" "/" \
    "$WEB_READY_TIMEOUT" "$WEB_PROCESS_FILE" "$WEB_LOG" || return 1
  log "三个服务均已就绪，访问 http://localhost:${WEB_PORT}（默认入口 /copilot）"
  cmd_status
}

cmd_stop() {
  stop_tracked_process "$WEB_PROCESS_FILE"
  stop_port "$WEB_PORT"
  stop_tracked_process "$AGENT_PROCESS_FILE"
  stop_port "$AGENT_PORT"
  stop_tracked_process "$JAVA_PROCESS_FILE"
  stop_port "$JAVA_PORT"
  log "全部服务已停止，端口已释放"
}

cmd_restart() {
  cmd_stop
  sleep 2
  cmd_start
}

cmd_status() {
  echo "===== 服务状态 ====="
  for entry in \
    "Java:$JAVA_PORT:/api/agent/tools:$JAVA_PROCESS_FILE" \
    "Agent:$AGENT_PORT:/health:$AGENT_PROCESS_FILE" \
    "Web:$WEB_PORT:/:$WEB_PROCESS_FILE"; do
    name="${entry%%:*}"
    rest="${entry#*:}"
    port="${rest%%:*}"
    rest="${rest#*:}"
    path="${rest%%:*}"
    pid_file="${rest#*:}"
    if curl -sf -m 2 -o /dev/null "http://127.0.0.1:$port$path" 2>/dev/null; then
      log "$name :$port 已就绪"
    elif port_busy "$port" || tracked_pid "$pid_file" >/dev/null; then
      log "$name :$port 进程运行但未就绪"
    else
      log "$name :$port 未运行"
    fi
  done
  echo "===== 日志 ====="
  log "tail -f $JAVA_LOG / $AGENT_LOG / $WEB_LOG"
}

cmd_logs() {
  local svc="${1:-all}"
  case "$svc" in
    java) tail -50 "$JAVA_LOG" ;;
    agent) tail -50 "$AGENT_LOG" ;;
    web) tail -50 "$WEB_LOG" ;;
    all)
      echo "===== Java ====="; tail -30 "$JAVA_LOG"
      echo "===== Agent ====="; tail -30 "$AGENT_LOG"
      echo "===== Web ====="; tail -30 "$WEB_LOG"
      ;;
    *) error "未知服务: （可用 java|agent|web）"; exit 1 ;;
  esac
}

cmd_stop_port() {
  local port="${1:-}"
  if [ -z "$port" ]; then
    error "用法: ./scripts/dev.sh stop-port <port>"
    exit 1
  fi
  stop_port "$port"
}

# ===== 入口 =====
case "${1:-}" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  restart) cmd_restart ;;
  status) cmd_status ;;
  logs) cmd_logs "${2:-all}" ;;
  stop-port) cmd_stop_port "${2:-}" ;;
  *)
    error "用法: $0 {start|stop|restart|status|logs [java|agent|web]|stop-port <port>}"
    exit 1
    ;;
esac
