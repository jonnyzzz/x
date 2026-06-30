#!/bin/bash
# Unified agent runner. Creates a new run_XXX folder, runs the agent, and blocks until completion.
# Usage: ./run-agent.sh [agent] [cwd] [prompt_file]
#
# Source: https://run-agent.jonnyzzz.com/run-agent.sh
# Docs:   https://run-agent.jonnyzzz.com/
#
# Copyright 2026 Eugene Petrenko
# Licensed under the Apache License, Version 2.0
# https://run-agent.jonnyzzz.com/LICENSE
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNS_DIR="${RUNS_DIR:-$BASE_DIR/runs}"
mkdir -p "$RUNS_DIR"
RUNS_DIR="$(cd "$RUNS_DIR" && pwd)"
MESSAGE_BUS="$RUNS_DIR/MESSAGE-BUS.md"
export RUNS_DIR
export MESSAGE_BUS

# All agents that have a case entry below (source of truth for invocation)
BUILTIN_AGENTS="codex claude gemini"

# RUN_AGENT_AGENTS overrides which agents are available (must be a subset of BUILTIN_AGENTS).
# Default: all built-in agents. Set to e.g. "claude,codex" to hide gemini from help/validation.
if [ -n "${RUN_AGENT_AGENTS:-}" ]; then
  KNOWN_AGENTS=""
  IFS=',' read -ra _req_agents <<< "$RUN_AGENT_AGENTS"
  for _ra in "${_req_agents[@]}"; do
    _ra="$(echo "$_ra" | tr -d '[:space:]')"
    [ -z "$_ra" ] && continue
    _is_builtin=false
    for _ba in $BUILTIN_AGENTS; do
      if [ "$_ra" = "$_ba" ]; then
        _is_builtin=true
        break
      fi
    done
    if [ "$_is_builtin" = false ]; then
      echo "RUN_AGENT_AGENTS: unknown agent '$_ra'. Built-in agents: ${BUILTIN_AGENTS// /,}" >&2
      exit 2
    fi
    KNOWN_AGENTS="${KNOWN_AGENTS:+$KNOWN_AGENTS }$_ra"
  done
  if [ -z "$KNOWN_AGENTS" ]; then
    echo "RUN_AGENT_AGENTS: no valid agents specified. Built-in agents: ${BUILTIN_AGENTS// /,}" >&2
    exit 2
  fi
else
  KNOWN_AGENTS="$BUILTIN_AGENTS"
fi

show_help() {
  cat <<HELP
run-agent.sh — Unified AI Agent runner

Usage: ./run-agent.sh <agent> <cwd> <prompt_file>

Arguments:
  agent        Agent to run: any,${KNOWN_AGENTS// /,}
               Use "any" to pick a random agent from the available list.
  cwd          Working directory for the agent (default: script directory)
  prompt_file  Path to the prompt file (default: ./prompt.md)

Configuration (env variables):
  RUNS_DIR            Runs output directory (default: ./runs)
  RUN_AGENT_AGENTS    Comma-separated list of available agents (default: all built-in)
  RUN_AGENT_TIMEOUT_SECONDS
                      Wall-clock timeout for the agent process (default: 3600, 0 disables)
  RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS
                      Dump diagnostics once after this many seconds with no stdout/stderr bytes
                      (default: 180, 0 disables)
  RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS
                      Terminate after this many seconds with no stdout/stderr bytes
                      (default: 0, disabled)
  RUN_AGENT_CLAUDE_ALLOW_TEXT_NO_OUTPUT_TIMEOUT
                      Set to 1 to allow RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS for
                      claude -p text-mode runs. By default those runs only emit
                      a final answer, so silence triggers diagnostics but not a
                      no-output kill.
  RUN_AGENT_HEARTBEAT_SECONDS
                      Update heartbeat.txt while the agent is running (default: 30, 0 disables)
  RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS
                      Timeout for individual diagnostics commands (default: 20)
  RUN_AGENT_THREAD_DUMP_TIMEOUT_SECONDS
                      Timeout for each JVM thread dump in diagnostics (default: 5)
  RUN_AGENT_THREAD_DUMP_MAX_JVMS
                      Maximum JVMs to thread-dump during agent diagnostics (default: 5)

Exported to agent process:
  RUNS_DIR            Absolute path to the runs directory
  MESSAGE_BUS         Absolute path to MESSAGE-BUS.md (inside RUNS_DIR)
  RUN_ID              Unique run identifier for this invocation
  PROMPT              Absolute path to the copied prompt file

Exit codes:
  0    Agent completed successfully
  1    Prompt file not found
  2    Unknown agent type
  129  Terminated by SIGHUP
  130  Terminated by SIGINT
  143  Terminated by SIGTERM

Output:
  Each run creates a directory under RUNS_DIR with:
    prompt.md          Copy of the prompt
    agent-stdout.txt   Agent stdout
    agent-stderr.txt   Agent stderr
    run-info.txt       Run metadata (RUN_ID, CWD, AGENT, CMD, EXIT_CODE, ...)
    run-agent.sh       Copy of the runner script
    pid.txt            Agent PID (removed on completion)

Source: https://run-agent.jonnyzzz.com/run-agent.sh
Docs:   https://run-agent.jonnyzzz.com/
HELP
}

# Handle help flags
case "${1:-}" in
  -h|--help|help)
    show_help
    exit 0
    ;;
esac

AGENT="${1:-any}"
CWD="${2:-$BASE_DIR}"
PROMPT_FILE="${3:-$BASE_DIR/prompt.md}"

# "any" picks a random agent from KNOWN_AGENTS
if [ "$AGENT" = "any" ]; then
  # shellcheck disable=SC2206
  _agents_arr=($KNOWN_AGENTS)
  AGENT="${_agents_arr[$((RANDOM % ${#_agents_arr[@]}))]}"
  echo "AGENT_SELECTED=$AGENT"
fi

# Validate agent name: must be alphanumeric/underscore and in KNOWN_AGENTS
if [[ ! "$AGENT" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]]; then
  echo "Unknown agent: $AGENT" >&2
  echo "Known agents: ${KNOWN_AGENTS// /,}" >&2
  exit 2
fi

_agent_known=false
for _ka in $KNOWN_AGENTS; do
  if [ "$_ka" = "$AGENT" ]; then
    _agent_known=true
    break
  fi
done
if [ "$_agent_known" = false ]; then
  echo "Unknown agent: $AGENT" >&2
  echo "Known agents: ${KNOWN_AGENTS// /,}" >&2
  exit 2
fi

# Normalize prompt path to absolute
PROMPT_FILE="$(cd "$(dirname "$PROMPT_FILE")" && pwd)/$(basename "$PROMPT_FILE")"
if [ ! -f "$PROMPT_FILE" ]; then
  echo "Prompt file not found: $PROMPT_FILE" >&2
  exit 1
fi

# Normalize CWD to absolute path
CWD="$(cd "$CWD" && pwd)"

# Sanitize environment: remove CLAUDECODE to avoid leaking nested runtime context
unset CLAUDECODE

# Build agent command array — properly quoted, no eval needed.
# To add a new agent, add a case entry here and update BUILTIN_AGENTS above.
AGENT_CMD=()
case "$AGENT" in
  codex)
    AGENT_CMD=(codex exec --dangerously-bypass-approvals-and-sandbox -C "$CWD" -)
    ;;
  claude)
    AGENT_CMD=(claude -p --input-format text --output-format text --tools default --permission-mode bypassPermissions)
    ;;
  gemini)
    AGENT_CMD=(gemini --screen-reader true --sandbox=false --approval-mode yolo --include-directories "$CWD")
    ;;
  *)
    echo "Unknown agent: $AGENT" >&2
    echo "Known agents: ${KNOWN_AGENTS// /,}" >&2
    exit 2
    ;;
esac

RUN_ID="run_$(date -u +%Y%m%d-%H%M%S)-$$"
RUN_DIR="$RUNS_DIR/$RUN_ID"
mkdir -p "$RUN_DIR"
cp "$BASE_DIR/run-agent.sh" "$RUN_DIR/run-agent.sh"

# Compose a CMD= field that can be pasted into a shell verbatim.
# printf %q quotes each token so spaces / shell metacharacters in $CWD
# or $PROMPT do not corrupt the field when readers split on whitespace.
_quoted_cmd=""
for _arg in "${AGENT_CMD[@]}"; do
  _quoted_cmd+="$(printf '%q' "$_arg") "
done

# Export variables available to the agent process
PROMPT="$RUN_DIR/prompt.md"
export RUN_ID
export PROMPT

# Standard file names
STDOUT_FILE="$RUN_DIR/agent-stdout.txt"
STDERR_FILE="$RUN_DIR/agent-stderr.txt"
PID_FILE="$RUN_DIR/pid.txt"
RUN_INFO_FILE="$RUN_DIR/run-info.txt"

cp "$PROMPT_FILE" "$PROMPT"

CMDLINE="${_quoted_cmd}< $(printf '%q' "$PROMPT")"

RUN_AGENT_TIMEOUT_SECONDS="${RUN_AGENT_TIMEOUT_SECONDS:-3600}"
RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS="${RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS:-180}"
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS="${RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS:-0}"
RUN_AGENT_HEARTBEAT_SECONDS="${RUN_AGENT_HEARTBEAT_SECONDS:-30}"
RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS="${RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS:-20}"
RUN_AGENT_THREAD_DUMP_TIMEOUT_SECONDS="${RUN_AGENT_THREAD_DUMP_TIMEOUT_SECONDS:-5}"
RUN_AGENT_THREAD_DUMP_MAX_JVMS="${RUN_AGENT_THREAD_DUMP_MAX_JVMS:-5}"
RUN_AGENT_POLL_SECONDS="${RUN_AGENT_POLL_SECONDS:-5}"
RUN_AGENT_CLAUDE_ALLOW_TEXT_NO_OUTPUT_TIMEOUT="${RUN_AGENT_CLAUDE_ALLOW_TEXT_NO_OUTPUT_TIMEOUT:-0}"
EFFECTIVE_NO_OUTPUT_TIMEOUT_SECONDS="$RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS"
NO_OUTPUT_TIMEOUT_NOTE=""
if [ "$AGENT" = "claude" ] && \
   [ "$RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS" -gt 0 ] && \
   [ "$RUN_AGENT_CLAUDE_ALLOW_TEXT_NO_OUTPUT_TIMEOUT" != "1" ]; then
  EFFECTIVE_NO_OUTPUT_TIMEOUT_SECONDS=0
  NO_OUTPUT_TIMEOUT_NOTE="disabled for claude text output; set RUN_AGENT_CLAUDE_ALLOW_TEXT_NO_OUTPUT_TIMEOUT=1 to force it"
fi

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

latest_output_mtime() {
  local stdout_mtime stderr_mtime
  stdout_mtime="$(file_mtime "$STDOUT_FILE")"
  stderr_mtime="$(file_mtime "$STDERR_FILE")"
  if [ "$stdout_mtime" -gt "$stderr_mtime" ]; then
    echo "$stdout_mtime"
  else
    echo "$stderr_mtime"
  fi
}

output_size() {
  local stdout_size stderr_size
  stdout_size="$(wc -c < "$STDOUT_FILE" 2>/dev/null || echo 0)"
  stderr_size="$(wc -c < "$STDERR_FILE" 2>/dev/null || echo 0)"
  echo $((stdout_size + stderr_size))
}

run_bounded() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$seconds" "$@"
  else
    "$@"
  fi
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

dump_diagnostics() {
  local reason="$1"
  local safe_reason diag_file
  safe_reason="$(printf '%s' "$reason" | tr -cd '[:alnum:]_-')"
  diag_file="$RUN_DIR/diagnostics-${safe_reason}-$(date -u +%Y%m%d-%H%M%S).txt"
  {
    echo "REASON=$reason"
    echo "RUN_ID=$RUN_ID"
    echo "AGENT=$AGENT"
    echo "PID=$AGENT_PID"
    echo "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "== child process =="
    ps -p "$AGENT_PID" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    if command -v pgrep >/dev/null 2>&1; then
      echo
      echo "== child processes =="
      pgrep -P "$AGENT_PID" -a 2>/dev/null || true
    fi
    if command -v sample >/dev/null 2>&1; then
      local sample_file="$RUN_DIR/sample-${safe_reason}-${AGENT_PID}.txt"
      echo
      echo "== process sample =="
      run_bounded "$RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" \
        sample "$AGENT_PID" 3 -file "$sample_file" >/dev/null 2>&1 && echo "$sample_file" || true
    fi
    echo
    echo "== matching processes =="
    ps -axo pid,ppid,stat,etime,comm 2>/dev/null | \
      egrep 'codex|claude|gemini|gradle|java|run-agent' | \
      egrep -v 'egrep|diagnostics-' || true
    echo
    echo "== jps =="
    if command -v jps >/dev/null 2>&1; then
      run_bounded "$RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" jps -lm 2>/dev/null || true
    else
      echo "jps not found"
    fi
    echo
    echo "== java thread dumps =="
    if command -v jps >/dev/null 2>&1; then
      java_pids=""
      for descendant_pid in $(descendant_pids "$AGENT_PID"); do
        descendant_command="$(ps -p "$descendant_pid" -o comm= 2>/dev/null || true)"
        case "$descendant_command" in
          *java*) java_pids="${java_pids:+$java_pids }$descendant_pid" ;;
        esac
      done
      if [ -z "$java_pids" ]; then
        java_pids="$(run_bounded "$RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" jps -q 2>/dev/null | head -"$RUN_AGENT_THREAD_DUMP_MAX_JVMS" || true)"
      else
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
    fi
    echo
    echo "== stdout tail =="
    tail -120 "$STDOUT_FILE" 2>/dev/null || true
    echo
    echo "== stderr tail =="
    tail -120 "$STDERR_FILE" 2>/dev/null || true
  } >"$diag_file" 2>&1 || true
  echo "DIAGNOSTICS=$diag_file"
  { echo "DIAGNOSTICS=$diag_file" >> "$RUN_INFO_FILE"; } || true
}

# Signal handler. AGENT_PID may still be empty if the trap fires before
# the fork; the guards make that case a no-op.
AGENT_PID=""
on_signal() {
  local sig="$1" code="$2"
  if [ -n "$AGENT_PID" ]; then
    kill -TERM "$AGENT_PID" 2>/dev/null || true
    wait "$AGENT_PID" 2>/dev/null || true
  fi
  rm -f "$PID_FILE" 2>/dev/null || true
  { printf 'EXIT_CODE=%s\nSIGNAL=%s\n' "$code" "$sig" >> "$RUN_INFO_FILE"; } || true
  exit "$code"
}
trap 'on_signal INT  130' INT
trap 'on_signal TERM 143' TERM
trap 'on_signal HUP  129' HUP

(
  cd "$CWD"
  exec "${AGENT_CMD[@]}" <"$PROMPT" 1>"$STDOUT_FILE" 2>"$STDERR_FILE"
) &
AGENT_PID=$!

# run-info.txt lands before pid.txt so a watcher that uses pid.txt as
# the "agent is up" signal can rely on run-info.txt already being
# readable when it fires.
RUN_INFO_BLOCK="RUN_ID=$RUN_ID
RUN_DIR=$RUN_DIR
CWD=$CWD
AGENT=$AGENT
CMD=$CMDLINE
PROMPT=$PROMPT
STDOUT=$STDOUT_FILE
STDERR=$STDERR_FILE
PID=$AGENT_PID
TIMEOUT_SECONDS=$RUN_AGENT_TIMEOUT_SECONDS
NO_OUTPUT_DIAGNOSTICS_SECONDS=$RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS
NO_OUTPUT_TIMEOUT_SECONDS=$RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS
EFFECTIVE_NO_OUTPUT_TIMEOUT_SECONDS=$EFFECTIVE_NO_OUTPUT_TIMEOUT_SECONDS"
if [ -n "$NO_OUTPUT_TIMEOUT_NOTE" ]; then
  RUN_INFO_BLOCK="$RUN_INFO_BLOCK
NO_OUTPUT_TIMEOUT_NOTE=$NO_OUTPUT_TIMEOUT_NOTE"
fi
printf '%s\n' "$RUN_INFO_BLOCK"
{ printf '%s\n' "$RUN_INFO_BLOCK" > "$RUN_INFO_FILE"; } || true
echo "$AGENT_PID" > "$PID_FILE"

EXIT_CODE=0
START_SECONDS="$(now_seconds)"
LAST_HEARTBEAT_SECONDS=0
NO_OUTPUT_DIAGNOSTICS_FIRED=false
TIMEOUT_FIRED=false
TIMEOUT_REASON=""
while kill -0 "$AGENT_PID" 2>/dev/null; do
  sleep "$RUN_AGENT_POLL_SECONDS"
  NOW_SECONDS="$(now_seconds)"
  ELAPSED_SECONDS=$((NOW_SECONDS - START_SECONDS))
  OUTPUT_SIZE="$(output_size)"
  if [ "$RUN_AGENT_HEARTBEAT_SECONDS" -gt 0 ] && \
     [ $((NOW_SECONDS - LAST_HEARTBEAT_SECONDS)) -ge "$RUN_AGENT_HEARTBEAT_SECONDS" ]; then
    {
      echo "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "PID=$AGENT_PID"
      echo "ELAPSED_SECONDS=$ELAPSED_SECONDS"
      echo "OUTPUT_BYTES=$OUTPUT_SIZE"
      ps -p "$AGENT_PID" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    } > "$RUN_DIR/heartbeat.txt" 2>/dev/null || true
    LAST_HEARTBEAT_SECONDS="$NOW_SECONDS"
  fi
  if [ "$OUTPUT_SIZE" -eq 0 ]; then
    if [ "$NO_OUTPUT_DIAGNOSTICS_FIRED" = false ] && \
       [ "$RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS" -gt 0 ] && \
       [ "$ELAPSED_SECONDS" -ge "$RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS" ]; then
      NO_OUTPUT_DIAGNOSTICS_FIRED=true
      dump_diagnostics "no-output-${RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS}s"
    fi
    if [ "$EFFECTIVE_NO_OUTPUT_TIMEOUT_SECONDS" -gt 0 ] && \
       [ "$ELAPSED_SECONDS" -ge "$EFFECTIVE_NO_OUTPUT_TIMEOUT_SECONDS" ]; then
      TIMEOUT_FIRED=true
      TIMEOUT_REASON="no-output-timeout-${EFFECTIVE_NO_OUTPUT_TIMEOUT_SECONDS}s"
      dump_diagnostics "$TIMEOUT_REASON"
      kill -TERM "$AGENT_PID" 2>/dev/null || true
      sleep 5
      kill -KILL "$AGENT_PID" 2>/dev/null || true
      wait "$AGENT_PID" 2>/dev/null || true
      EXIT_CODE=124
      break
    fi
  fi
  if [ "$RUN_AGENT_TIMEOUT_SECONDS" -gt 0 ]; then
    if [ "$ELAPSED_SECONDS" -ge "$RUN_AGENT_TIMEOUT_SECONDS" ]; then
      TIMEOUT_FIRED=true
      TIMEOUT_REASON="timeout-${RUN_AGENT_TIMEOUT_SECONDS}s"
      dump_diagnostics "$TIMEOUT_REASON"
      kill -TERM "$AGENT_PID" 2>/dev/null || true
      sleep 5
      kill -KILL "$AGENT_PID" 2>/dev/null || true
      wait "$AGENT_PID" 2>/dev/null || true
      EXIT_CODE=124
      break
    fi
  fi
done

if [ "$TIMEOUT_FIRED" = false ]; then
  wait "$AGENT_PID" || EXIT_CODE=$?
fi
rm -f "$PID_FILE"

# Record EXIT_CODE on both surfaces, but never let a failed write here
# shadow the agent's exit code (e.g. disk full, run dir removed mid-run).
echo "EXIT_CODE=$EXIT_CODE"
if [ "$TIMEOUT_FIRED" = true ]; then
  echo "TIMEOUT_SECONDS=$RUN_AGENT_TIMEOUT_SECONDS"
  {
    echo "TIMEOUT_SECONDS=$RUN_AGENT_TIMEOUT_SECONDS"
    echo "TIMEOUT_REASON=$TIMEOUT_REASON"
  } >> "$RUN_INFO_FILE" || true
fi
{ echo "EXIT_CODE=$EXIT_CODE" >> "$RUN_INFO_FILE"; } || true
exit "$EXIT_CODE"
