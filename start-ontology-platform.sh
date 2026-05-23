#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${ROOT_DIR}/ontology-lite/ontology-server"
LOG_DIR="${ROOT_DIR}/ontology-lite/runtime-logs"
PID_DIR="${ROOT_DIR}/ontology-lite/runtime-pids"
DATA_DIR="${ROOT_DIR}/ontology-lite/runtime-data"
ENV_FILE="${ROOT_DIR}/ontology-lite/runtime-config/ontology-platform.env"
APP_PACKAGE="${APP_DIR}/target/ontology-server-0.1.0.jar"
APP_NAME="ontology-server"
SCREEN_NAME="ont-ontology-server"
ACTION="${1:-start}"

mkdir -p "${LOG_DIR}" "${PID_DIR}" "${DATA_DIR}" "$(dirname "${ENV_FILE}")"

if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

APP_PORT="${APP_PORT:-9000}"
APP_HOST="${APP_HOST:-127.0.0.1}"
ONTOLOGY_DB_URL="${ONTOLOGY_DB_URL:-jdbc:h2:file:${DATA_DIR}/ontology-platform;MODE=MySQL;DATABASE_TO_UPPER=false;AUTO_SERVER=TRUE}"
ONTOLOGY_DB_DRIVER="${ONTOLOGY_DB_DRIVER:-org.h2.Driver}"
ONTOLOGY_DB_USERNAME="${ONTOLOGY_DB_USERNAME:-sa}"
ONTOLOGY_DB_PASSWORD="${ONTOLOGY_DB_PASSWORD:-}"

info() {
  printf '[INFO] %s\n' "$*"
}

warn() {
  printf '[WARN] %s\n' "$*" >&2
}

fail() {
  printf '[ERROR] %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令：$1"
}

jdbc_host_port() {
  local jdbc_url="$1"
  if [[ "${jdbc_url}" =~ ^jdbc:mysql://([^/:?]+):([0-9]+) ]]; then
    printf '%s %s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
    return 0
  fi
  return 1
}

can_reach_tcp() {
  local host="$1"
  local port="$2"
  python3 - "$host" "$port" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
try:
    with socket.create_connection((host, port), timeout=3):
        pass
except OSError:
    sys.exit(1)
PY
}

prepare_runtime_database() {
  local host_port host port h2_url
  h2_url="jdbc:h2:file:${DATA_DIR}/ontology-platform;MODE=MySQL;DATABASE_TO_UPPER=false;AUTO_SERVER=TRUE"
  if [[ "${ONTOLOGY_DB_URL}" == jdbc:h2:* ]]; then
    return 0
  fi
  if host_port="$(jdbc_host_port "${ONTOLOGY_DB_URL}")"; then
    host="${host_port%% *}"
    port="${host_port##* }"
    if can_reach_tcp "${host}" "${port}"; then
      return 0
    fi
    warn "平台持久化库 ${host}:${port} 当前不可达，启动自动切换到本地 H2 兜底库。"
    ONTOLOGY_DB_URL="${h2_url}"
    ONTOLOGY_DB_DRIVER="org.h2.Driver"
    ONTOLOGY_DB_USERNAME="sa"
    ONTOLOGY_DB_PASSWORD=""
  fi
}

java_bin() {
  if [ -n "${JAVA17_HOME:-}" ] && [ -x "${JAVA17_HOME}/bin/java" ]; then
    printf '%s\n' "${JAVA17_HOME}/bin/java"
    return 0
  fi
  if [ -x "/usr/local/opt/openjdk@17/bin/java" ]; then
    printf '%s\n' "/usr/local/opt/openjdk@17/bin/java"
    return 0
  fi
  if [ -x "/opt/homebrew/opt/openjdk@17/bin/java" ]; then
    printf '%s\n' "/opt/homebrew/opt/openjdk@17/bin/java"
    return 0
  fi
  if [ -x "/usr/local/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin/java" ]; then
    printf '%s\n' "/usr/local/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin/java"
    return 0
  fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] && is_java_17_plus "${JAVA_HOME}/bin/java"; then
    printf '%s\n' "${JAVA_HOME}/bin/java"
    return 0
  fi
  if command -v java >/dev/null 2>&1 && is_java_17_plus "$(command -v java)"; then
    command -v java
    return 0
  fi
  fail "找不到可用的 JDK 17+。请安装 openjdk@17，或通过 JAVA17_HOME 指定。"
}

is_java_17_plus() {
  local java="$1"
  local version major
  version="$("${java}" -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  major="${version%%.*}"
  if [ "${major}" = "1" ]; then
    major="$(printf '%s' "${version}" | awk -F '[._]' '{print $2}')"
  fi
  [ "${major:-0}" -ge 17 ]
}

assert_java_17_plus() {
  local java="$1"
  is_java_17_plus "${java}" || fail "当前 Java 版本低于 17：$("${java}" -version 2>&1 | head -n 1)"
}

port_pid() {
  lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

is_port_listening() {
  [ -n "$(port_pid "$1")" ]
}

wait_port() {
  local port="$1"
  local name="$2"
  local timeout="${3:-60}"
  local elapsed=0

  while [ "${elapsed}" -lt "${timeout}" ]; do
    if is_port_listening "${port}"; then
      info "${name} 已监听端口 ${port}"
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  fail "${name} 未能在 ${timeout}s 内监听端口 ${port}，请查看日志：${LOG_DIR}/${APP_NAME}.log"
}

stop_port() {
  local port="$1"
  local name="$2"
  local pid
  pid="$(port_pid "${port}")"
  if [ -z "${pid}" ]; then
    return 0
  fi

  info "停止 ${name}，占用端口 ${port} 的 PID=${pid}"
  kill "${pid}" 2>/dev/null || true
  sleep 2

  pid="$(port_pid "${port}")"
  if [ -n "${pid}" ]; then
    warn "${name} 未正常退出，执行 kill -9 PID=${pid}"
    kill -9 "${pid}" 2>/dev/null || true
  fi
}

build_app() {
  require_cmd mvn
  [ -d "${APP_DIR}" ] || fail "找不到应用目录：${APP_DIR}"

  info "构建 ${APP_NAME}"
  (cd "${APP_DIR}" && mvn package -Dmaven.test.skip=true)
  [ -f "${APP_PACKAGE}" ] || fail "构建后未找到运行包：${APP_PACKAGE}"
}

start_app() {
  require_cmd lsof
  require_cmd screen
  local java
  java="$(java_bin)"
  assert_java_17_plus "${java}"

  if is_port_listening "${APP_PORT}"; then
    info "${APP_NAME} 已在端口 ${APP_PORT} 运行，跳过启动"
    print_status
    printf '\n访问地址：http://%s:%s/\n' "${APP_HOST}" "${APP_PORT}"
    return 0
  fi

  build_app

  prepare_runtime_database
  info "启动 ${APP_NAME}，端口 ${APP_PORT}"
  screen -S "${SCREEN_NAME}" -X quit >/dev/null 2>&1 || true
  screen -dmS "${SCREEN_NAME}" /bin/bash -lc "export ONTOLOGY_DB_URL='${ONTOLOGY_DB_URL}' ONTOLOGY_DB_DRIVER='${ONTOLOGY_DB_DRIVER:-}' ONTOLOGY_DB_USERNAME='${ONTOLOGY_DB_USERNAME:-}' ONTOLOGY_DB_PASSWORD='${ONTOLOGY_DB_PASSWORD:-}' APP_HOST='${APP_HOST}'; exec '${java}' -Dfile.encoding=UTF-8 -Dserver.port='${APP_PORT}' -jar '${APP_PACKAGE}' > '${LOG_DIR}/${APP_NAME}.log' 2>&1"
  wait_port "${APP_PORT}" "${APP_NAME}" 90
  sleep 2
  is_port_listening "${APP_PORT}" || fail "${APP_NAME} 启动后退出，请查看日志：${LOG_DIR}/${APP_NAME}.log"
  port_pid "${APP_PORT}" > "${PID_DIR}/${APP_NAME}.pid"
  print_status
  printf '\n访问地址：http://%s:%s/\n' "${APP_HOST}" "${APP_PORT}"
}

stop_app() {
  require_cmd lsof
  screen -S "${SCREEN_NAME}" -X quit >/dev/null 2>&1 || true
  stop_port "${APP_PORT}" "${APP_NAME}"
  rm -f "${PID_DIR}/${APP_NAME}.pid"
}

print_status() {
  require_cmd lsof
  local pid status
  pid="$(port_pid "${APP_PORT}")"
  if [ -n "${pid}" ]; then
    status="RUNNING"
  else
    status="STOPPED"
    pid="-"
  fi

  printf '\n%-18s %-8s %-10s %s\n' "服务" "端口" "状态" "PID"
  printf '%-18s %-8s %-10s %s\n' "----" "----" "----" "---"
  printf '%-18s %-8s %-10s %s\n' "${APP_NAME}" "${APP_PORT}" "${status}" "${pid}"
}

show_logs() {
  printf '日志文件：%s/%s.log\n' "${LOG_DIR}" "${APP_NAME}"
  printf '查看命令：tail -f %s/%s.log\n' "${LOG_DIR}" "${APP_NAME}"
}

case "${ACTION}" in
  start)
    start_app
    ;;
  restart)
    stop_app
    start_app
    ;;
  stop)
    stop_app
    print_status
    ;;
  status)
    print_status
    ;;
  build)
    build_app
    ;;
  logs)
    show_logs
    ;;
  *)
    cat <<USAGE
用法：
  ./start-ontology-platform.sh          构建并启动本体平台
  ./start-ontology-platform.sh start    构建并启动本体平台
  ./start-ontology-platform.sh restart  重启本体平台
  ./start-ontology-platform.sh stop     停止本体平台
  ./start-ontology-platform.sh status   查看运行状态
  ./start-ontology-platform.sh build    只构建运行包
  ./start-ontology-platform.sh logs     查看日志位置

说明：
  - 当前轻量版为单体 Java 应用，不依赖外部中间件或独立前端仓库。
  - 默认读取 ontology-lite/runtime-config/ontology-platform.env；未配置时使用本地 H2 文件库。
  - 默认监听 127.0.0.1:9000，可通过 APP_HOST / APP_PORT 覆盖。
  - 默认使用 JAVA17_HOME/JAVA_HOME/java 中可用的 JDK 17+。
USAGE
    exit 1
    ;;
esac
