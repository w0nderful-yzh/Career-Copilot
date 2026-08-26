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

set -euo pipefail

# ===== 配置 =====
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_PORT="${SERVER_PORT:-8081}"
AGENT_PORT="${AGENT_PORT:-8001}"
WEB_PORT="${WEB_PORT:-5173}"
LOG_DIR="$ROOT_DIR/.dev-logs"
JAVA_LOG="$LOG_DIR/java.log"
AGENT_LOG="$LOG_DIR/agent.log"
WEB_LOG="$LOG_DIR/web.log"
JAVA_READY_TIMEOUT=90   # Java 启动较慢，最长等待秒数

mkdir -p "$LOG_DIR"

# ===== 工具函数 =====
log() { printf "[dev] %s\n" "$*"; }
error() { printf "[dev][ERROR] %s\n" "$*" >&2; }

# 按端口取 PID（端口被占时返回第一个 PID）
port_pid() { lsof -ti tcp:"$1" 2>/dev/null | head -1; }

port_busy() { [ -n "$(port_pid "$1")" ]; }

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

wait_java() {
  for _ in $(seq 1 "$JAVA_READY_TIMEOUT"); do
    if curl -sf -o /dev/null "http://127.0.0.1:$JAVA_PORT/api/agent/tools" 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
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
  cd "$ROOT_DIR"
  SERVER_PORT="$JAVA_PORT" nohup ./gradlew :app:bootRun --no-daemon >"$JAVA_LOG" 2>&1 &
  cd - >/dev/null
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
  cd "$ROOT_DIR/agent-service"
  source .venv/bin/activate
  # 环境变量优先于 .env，确保指向当前 Java 端口
  BACKEND_BASE_URL="http://127.0.0.1:$JAVA_PORT" \
    nohup uvicorn career_copilot.main:app --reload --port "$AGENT_PORT" >"$AGENT_LOG" 2>&1 &
  cd - >/dev/null
}

start_web() {
  if port_busy "$WEB_PORT"; then
    log "前端端口 $WEB_PORT 已被占用，跳过启动（如需重启先执行 stop）"
    return 0
  fi
  log "启动 React 前端 :${WEB_PORT}（日志 ${WEB_LOG}）"
  cd "$ROOT_DIR/frontend"
  # 环境变量覆盖 .env.development，确保代理指向当前端口
  VITE_API_PROXY_TARGET="http://127.0.0.1:$JAVA_PORT" \
  VITE_AGENT_PROXY_TARGET="http://127.0.0.1:$AGENT_PORT" \
    nohup pnpm dev --port "$WEB_PORT" >"$WEB_LOG" 2>&1 &
  cd - >/dev/null
}

cmd_start() {
  start_java
  start_python
  start_web
  log "等待 Java 就绪（最长 ${JAVA_READY_TIMEOUT}s）..."
  if wait_java; then
    log "三个服务已启动，访问 http://localhost:${WEB_PORT}（默认入口 /copilot）"
  else
    error "Java 启动超时，查看日志: tail -f $JAVA_LOG"
  fi
  cmd_status
}

cmd_stop() {
  stop_port "$WEB_PORT"
  stop_port "$AGENT_PORT"
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
  for entry in "Java:$JAVA_PORT:/api/agent/tools" "Agent:$AGENT_PORT:/health" "Web:$WEB_PORT:/"; do
    name="${entry%%:*}"
    rest="${entry#*:}"
    port="${rest%%:*}"
    path="${rest#*:}"
    if port_busy "$port"; then
      code="$(curl -sf -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port$path" 2>/dev/null || echo '未就绪')"
      log "$name :$port 运行中 (HTTP $code)"
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