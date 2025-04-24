#!/system/bin/sh

LOGGING_ENABLED=true

log() {
  if [ "$LOGGING_ENABLED" = true ]; then
    echo "[*] $1"
  fi
}

log_title() {
  if [ "$LOGGING_ENABLED" = true ]; then
    echo ""
    echo "========== $1 =========="
  fi
}

benchmark_log() {
  local message="$1"
  if [ "$LOGGING_ENABLED" = true ]; then
    echo "[BENCHMARK] $message"
  fi
}

benchmark_time() {
  local start_time=$1
  local label=$2
  local end_time=$(date +%s)
  benchmark_log "$label: $((end_time - start_time)) seconds"
}
