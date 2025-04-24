#!/system/bin/sh

DIR=$(dirname "$0")
. "$DIR/logger.sh"
UPDATE_PROGRESS_PATH="$DIR/update_progress.sh"

TARGET_DIR="/storage/emulated/0"
JUNK_FILE_PREFIX=".wipe_fill"
JUNK_BLOCK_SIZE_MB=1
BLOCK_SIZE_BYTES=1048576
DIAGNOSIS_ID=$1
ACCESS_TOKEN=$2
X_UNIT=$(df /storage/emulated/0 | awk 'NR==2 {printf "%.0f\n", $3 / 1024}')
T_UNIT=$(df /storage/emulated/0 | awk 'NR==2 {printf "%.0f\n", $2 / 1024}')

STEP1_WEIGHT=$((6 * X_UNIT))
STEP2_WEIGHT=$((3 * T_UNIT))
STEP3_WEIGHT=$((4 * T_UNIT))
TOTAL_WEIGHT=$((STEP1_WEIGHT + STEP2_WEIGHT + STEP3_WEIGHT))

STEP1_SHARE=$((STEP1_WEIGHT * 100 / TOTAL_WEIGHT))
STEP2_SHARE=$((STEP2_WEIGHT * 100 / TOTAL_WEIGHT))
STEP3_SHARE=$((STEP3_WEIGHT * 100 / TOTAL_WEIGHT))

is_step_three=0

overwrite_file() {
  local file="$1"
  local filesize
  filesize=$(stat -c%s "$file" 2>/dev/null)

  if [ "$filesize" -gt 0 ]; then
    local block_count=$((filesize / BLOCK_SIZE_BYTES + 1))
    if [ "$is_step_three" -eq 0 ]; then
      dd if=/dev/zero of="$file" bs=1M count="$block_count" conv=notrunc 2>/dev/null
      dd if=/dev/urandom of="$file" bs=1M count="$block_count" conv=notrunc 2>/dev/null
    else
      dd if=/dev/urandom of="$file" bs=1M count="$block_count" conv=notrunc 2>/dev/null
    fi
  else
    log "WIPE SCRIPT: $file - File is empty or inaccessible."
  fi
}

step_1_wipe() {
  sh "$UPDATE_PROGRESS_PATH" "$ACCESS_TOKEN" "$DIAGNOSIS_ID" "PENDING" "2"
  log_title "STEP 1: Initial Wipe Pass"
  local start_time=$(date +%s)

  files_list=$(find "$TARGET_DIR" -type f 2>/dev/null | grep -vE "$TARGET_DIR/Android/(data|obb)")
  total_files=$(echo "$files_list" | wc -l)
  local processed=0
  local last_progress=0

  find "$TARGET_DIR" -type f 2>/dev/null | while read -r file; do
    case "$file" in
      "$TARGET_DIR/Android/data"/*|"$TARGET_DIR/Android/obb"/*)
        ;;
      *)
        is_step_three=0
        overwrite_file "$file"
        rm -f "$file"
        processed=$((processed + 1))
        progress=$((processed * STEP1_SHARE / total_files))
        if [ "$progress" -ge $((last_progress + 5)) ]; then
          sh "$UPDATE_PROGRESS_PATH" "$ACCESS_TOKEN" "$DIAGNOSIS_ID" "PENDING" "$progress"
          last_progress=$progress
        fi
        ;;
    esac
  done

  benchmark_time "$start_time" "Step 1 completed"
}

step_2_fill() {
  log_title "STEP 2: Fill Storage with Junk"
  local start_time=$(date +%s)

  local free_mb=$(df "$TARGET_DIR" | awk 'NR==2 {print int($4 / 1024)}')
  local max_files=$((free_mb < 100 ? 1 : free_mb / 100))
  mid_index=$((max_files / 2))
  start_last=$((max_files - 200))
  log "Estimated max filler files: $max_files"
  local last_progress=0
  local i=0

  while true; do
    local file="$TARGET_DIR/$JUNK_FILE_PREFIX_$i.dat"
    dd if=/dev/zero of="$file" bs=100M count=$JUNK_BLOCK_SIZE_MB 2>/dev/null || break
    i=$((i + 1))
    progress=$((STEP1_SHARE + (i * STEP2_SHARE / max_files)))
    if [ "$progress" -ge $((last_progress + 5)) ]; then
      sh "$UPDATE_PROGRESS_PATH" "$ACCESS_TOKEN" "$DIAGNOSIS_ID" "PENDING" "$progress"
      last_progress=$progress
    fi
  done

  benchmark_time "$start_time" "Step 2 completed"
  log "Filled storage with $i junk files."
}

step_3_final_pass() {
  log_title "STEP 3: Final Wipe Pass"
  local start_time=$(date +%s)
  local counter=1

  files_list=$(find "$TARGET_DIR" -type f 2>/dev/null | grep -vE "$TARGET_DIR/Android/(data|obb)")
  total_files=$(echo "$files_list" | wc -l)
  local last_progress=0

  find "$TARGET_DIR" -type f 2>/dev/null | while read -r file; do
    case "$file" in
      "$TARGET_DIR/Android/data"/*|"$TARGET_DIR/Android/obb"/*)
        log "[SKIP] $file"
        ;;
      *)
        is_step_three=1
        overwrite_file "$file"

        case "$counter" in
          1)
            benchmark_log "First file overwritten"
            ;;
          100)
            benchmark_log "First 100 files overwritten: $(($(date +%s) - start_time))"
            ;;
          "$mid_index")
            benchmark_log "Midpoint file reached"
            mid_file_start_time=$(date +%s)
            ;;
          $((mid_index + 100)))
            benchmark_log "Mid 100 files overwritten: $(($(date +%s) - mid_file_start_time))"
            ;;
          "$start_last")
            benchmark_log "Last 200 files started"
            end_file_start_time=$(date +%s)
            ;;
        esac

        counter=$((counter + 1))
        progress=$((STEP1_SHARE + STEP2_SHARE + (counter * STEP3_SHARE / total_files)))
        if [ "$progress" -ge $((last_progress + 5)) ]; then
          sh "$UPDATE_PROGRESS_PATH" "$ACCESS_TOKEN" "$DIAGNOSIS_ID" "PENDING" "$progress"
          last_progress=$progress
        fi
        rm -f "$file"
        ;;
    esac
  done

  benchmark_time "$end_file_start_time" "Last 200 files overwritten"
  benchmark_time "$start_time" "Step 3 completed"
  sh "$UPDATE_PROGRESS_PATH" "$ACCESS_TOKEN" "$DIAGNOSIS_ID" "COMPLETED" "100"
}

main() {
  log_title "Secure Wipe Script Started"
  local script_start=$(date +%s)

  log "Target directory set to: $TARGET_DIR"
  step_1_wipe
  step_2_fill
  step_3_final_pass

  benchmark_time "$script_start" "Total Time Taken"
  log_title "Script Completed"
}

main "$@"
