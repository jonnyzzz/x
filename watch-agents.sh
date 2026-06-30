#!/bin/bash
# Periodically report agent status using PID files under runs/.
#
# Useful one-shot diagnostics:
#   RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_DIAGNOSE_STALE=1 ./watch-agents.sh
#
# Optional recovery for genuinely stale runs. This always writes diagnostics
# before terminating the agent:
#   RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_DIAGNOSE_STALE=1 \
#   RUN_AGENT_TERMINATE_STALE=1 RUN_AGENT_RESTART_STALE=1 ./watch-agents.sh
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNS_DIR="${RUNS_DIR:-$BASE_DIR/runs}"
LOG="$RUNS_DIR/agent-watch.log"
RUN_AGENT_WATCH_LIMIT="${RUN_AGENT_WATCH_LIMIT:-80}"
RUN_AGENT_STALE_SECONDS="${RUN_AGENT_STALE_SECONDS:-900}"
RUN_AGENT_WATCH_INTERVAL_SECONDS="${RUN_AGENT_WATCH_INTERVAL_SECONDS:-60}"
RUN_AGENT_WATCH_ONCE="${RUN_AGENT_WATCH_ONCE:-0}"
RUN_AGENT_DIAGNOSE_STALE="${RUN_AGENT_DIAGNOSE_STALE:-0}"
RUN_AGENT_TERMINATE_STALE="${RUN_AGENT_TERMINATE_STALE:-0}"
RUN_AGENT_RESTART_STALE="${RUN_AGENT_RESTART_STALE:-0}"
RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS="${RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS:-20}"
RUN_AGENT_THREAD_DUMP_TIMEOUT_SECONDS="${RUN_AGENT_THREAD_DUMP_TIMEOUT_SECONDS:-5}"
RUN_AGENT_THREAD_DUMP_MAX_JVMS="${RUN_AGENT_THREAD_DUMP_MAX_JVMS:-5}"

if [ "$RUN_AGENT_TERMINATE_STALE" = "1" ] && [ "$RUN_AGENT_DIAGNOSE_STALE" != "1" ]; then
  echo "RUN_AGENT_TERMINATE_STALE=1 requires RUN_AGENT_DIAGNOSE_STALE=1" >&2
  exit 2
fi

if [ "$RUN_AGENT_RESTART_STALE" = "1" ] && [ "$RUN_AGENT_TERMINATE_STALE" != "1" ]; then
  echo "RUN_AGENT_RESTART_STALE=1 requires RUN_AGENT_TERMINATE_STALE=1" >&2
  exit 2
fi

if [ "$RUN_AGENT_WATCH_ONCE" != "1" ] && { [ "$RUN_AGENT_TERMINATE_STALE" = "1" ] || [ "$RUN_AGENT_RESTART_STALE" = "1" ]; }; then
  echo "RUN_AGENT_TERMINATE_STALE/RUN_AGENT_RESTART_STALE require RUN_AGENT_WATCH_ONCE=1" >&2
  exit 2
fi

TIMEOUT_BIN=""
if command -v timeout >/dev/null 2>&1; then
  TIMEOUT_BIN="$(command -v timeout)"
elif command -v gtimeout >/dev/null 2>&1; then
  TIMEOUT_BIN="$(command -v gtimeout)"
fi

run_bounded() {
  local seconds="$1"
  shift
  if [ -n "$TIMEOUT_BIN" ]; then
    "$TIMEOUT_BIN" "$seconds" "$@"
  else
    "$@"
  fi
}

now_seconds() {
  date +%s
}

file_mtime() {
  local file="$1"
  if [ ! -e "$file" ]; then
    echo 0
    return
  fi
  stat -f %m "$file" 2>/dev/null || stat -c %Y "$file" 2>/dev/null || echo 0
}

latest_output_age() {
  local run_dir="$1"
  local stdout_mtime stderr_mtime latest now
  stdout_mtime="$(file_mtime "$run_dir/agent-stdout.txt")"
  stderr_mtime="$(file_mtime "$run_dir/agent-stderr.txt")"
  latest="$stdout_mtime"
  [ "$stderr_mtime" -gt "$latest" ] && latest="$stderr_mtime"
  if [ "$latest" -le 0 ]; then
    echo "unknown"
    return
  fi
  now="$(now_seconds)"
  echo "$((now - latest))s"
}

run_info_value() {
  local run_dir="$1"
  local key="$2"
  awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2); exit }' \
    "$run_dir/run-info.txt" 2>/dev/null || true
}

descendant_pids() {
  local frontier="$1"
  local children pid
  while [ -n "$frontier" ]; do
    children=""
    for pid in $frontier; do
      children="$children $(pgrep -P "$pid" 2>/dev/null || true)"
    done
    # shellcheck disable=SC2086
    set -- $children
    [ "$#" -gt 0 ] || break
    printf '%s\n' "$@"
    frontier="$*"
  done
}

diagnose_run() {
  local run_dir="$1"
  local pid="$2"
  local reason="$3"
  local diag_file="$run_dir/watch-diagnostics-${reason}-$(date -u +%Y%m%d-%H%M%S).txt"
  {
    echo "REASON=$reason"
    echo "RUN_DIR=$run_dir"
    echo "PID=$pid"
    echo "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "== run-info =="
    sed -n '1,120p' "$run_dir/run-info.txt" 2>/dev/null || true
    echo
    echo "== agent process =="
    ps -p "$pid" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    echo
    echo "== descendants =="
    for child in $(descendant_pids "$pid"); do
      ps -p "$child" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    done
    echo
    echo "== jps =="
    if command -v jps >/dev/null 2>&1; then
      run_bounded "$RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" jps -lm 2>/dev/null || true
    else
      echo "jps not found"
    fi
    echo
    echo "== java thread dumps =="
    java_pids=""
    for child in $(descendant_pids "$pid"); do
      command_name="$(ps -p "$child" -o comm= 2>/dev/null || true)"
      case "$command_name" in
        *java*) java_pids="${java_pids:+$java_pids }$child" ;;
      esac
    done
    if [ -z "$java_pids" ] && command -v jps >/dev/null 2>&1; then
      java_pids="$(run_bounded "$RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" jps -q 2>/dev/null | head -"$RUN_AGENT_THREAD_DUMP_MAX_JVMS" || true)"
    elif [ -n "$java_pids" ]; then
      java_pids="$(printf '%s\n' $java_pids | head -"$RUN_AGENT_THREAD_DUMP_MAX_JVMS" || true)"
    fi
    for java_pid in $java_pids; do
      [ -n "$java_pid" ] || continue
      echo "-- pid $java_pid --"
      if command -v jcmd >/dev/null 2>&1; then
        run_bounded "$RUN_AGENT_THREAD_DUMP_TIMEOUT_SECONDS" jcmd "$java_pid" Thread.print 2>&1 || true
      elif command -v jstack >/dev/null 2>&1; then
        run_bounded "$RUN_AGENT_THREAD_DUMP_TIMEOUT_SECONDS" jstack "$java_pid" 2>&1 || true
      else
        echo "jcmd/jstack not found"
      fi
    done
    echo
    echo "== stdout tail =="
    tail -120 "$run_dir/agent-stdout.txt" 2>/dev/null || true
    echo
    echo "== stderr tail =="
    tail -120 "$run_dir/agent-stderr.txt" 2>/dev/null || true
  } >"$diag_file" 2>&1 || true
  echo "  diagnostics: $diag_file" | tee -a "$LOG"
}

terminate_run() {
  local run_dir="$1"
  local pid="$2"
  echo "  terminating stale PID $pid for $run_dir" | tee -a "$LOG"
  kill -TERM "$pid" 2>/dev/null || true
  sleep 5
  kill -KILL "$pid" 2>/dev/null || true
  rm -f "$run_dir/pid.txt" 2>/dev/null || true
}

restart_run() {
  local run_dir="$1"
  local agent cwd prompt
  agent="$(run_info_value "$run_dir" AGENT)"
  cwd="$(run_info_value "$run_dir" CWD)"
  prompt="$run_dir/prompt.md"
  if [ -z "$agent" ] || [ -z "$cwd" ] || [ ! -f "$prompt" ]; then
    echo "  restart skipped: missing AGENT/CWD/prompt for $run_dir" | tee -a "$LOG"
    return
  fi
  echo "  restarting $agent for $run_dir" | tee -a "$LOG"
  (
    RUN_AGENT_TIMEOUT_SECONDS="${RUN_AGENT_RESTART_TIMEOUT_SECONDS:-900}" \
    RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS="${RUN_AGENT_RESTART_NO_OUTPUT_DIAGNOSTICS_SECONDS:-180}" \
    RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS="${RUN_AGENT_RESTART_NO_OUTPUT_TIMEOUT_SECONDS:-0}" \
    "$BASE_DIR/run-agent.sh" "$agent" "$cwd" "$prompt"
  ) >>"$LOG" 2>&1 &
}

while true; do
  ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  echo "[$ts] status check (latest $RUN_AGENT_WATCH_LIMIT runs)" | tee -a "$LOG"
  found=0
  while IFS= read -r run_dir; do
    [ -n "$run_dir" ] || continue
    found=1
    pid_file="$run_dir/pid.txt"
    if [ -f "$pid_file" ]; then
      pid=$(cat "$pid_file" || true)
      if [ -z "$pid" ]; then
        echo "  $run_dir: PID file empty" | tee -a "$LOG"
        continue
      fi
      if ps -p "$pid" >/dev/null 2>&1; then
        output_age="$(latest_output_age "$run_dir")"
        stale_note=""
        stale=false
        if [[ "$output_age" =~ ^[0-9]+s$ ]]; then
          age_number="${output_age%s}"
          if [ "$age_number" -ge "$RUN_AGENT_STALE_SECONDS" ]; then
            stale_note=" STALE_OUTPUT"
            stale=true
          fi
        fi
        echo "  $run_dir: PID $pid running output_age=$output_age$stale_note" | tee -a "$LOG"
        if [ "$stale" = true ] && [ "$RUN_AGENT_DIAGNOSE_STALE" = "1" ]; then
          diagnose_run "$run_dir" "$pid" "stale-output-${RUN_AGENT_STALE_SECONDS}s"
          if [ "$RUN_AGENT_TERMINATE_STALE" = "1" ]; then
            terminate_run "$run_dir" "$pid"
            if [ "$RUN_AGENT_RESTART_STALE" = "1" ]; then
              restart_run "$run_dir"
            fi
          fi
        fi
      else
        echo "  $run_dir: PID $pid finished" | tee -a "$LOG"
      fi
      continue
    fi
    if rg -q "EXIT_CODE=" "$run_dir/run-info.txt" 2>/dev/null; then
      echo "  $run_dir: finished (exit recorded)" | tee -a "$LOG"
    else
      echo "  $run_dir: unknown (no pid/exit)" | tee -a "$LOG"
    fi
  done < <(ls -td "$RUNS_DIR"/run_* 2>/dev/null | head -n "$RUN_AGENT_WATCH_LIMIT")

  if [ $found -eq 0 ]; then
    echo "  no runs found" | tee -a "$LOG"
  fi

  if [ "$RUN_AGENT_WATCH_ONCE" = "1" ]; then
    break
  fi

  sleep "$RUN_AGENT_WATCH_INTERVAL_SECONDS"
 done
