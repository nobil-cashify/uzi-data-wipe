#!/system/bin/sh

TARGET_DIR="/storage/emulated/0"
JUNK_FILE_PREFIX=".wipe_fill"
JUNK_BLOCK_SIZE_MB=100
BLOCK_SIZE_BYTES=1048576
DIAGNOSIS_ID=$1

# Utility Functions:
log() {
  echo "[*] $1"
}

log_title() {
  echo ""
  echo "========== $1 =========="
}

# Function to update progress
update_progress() {
  DIAGNOSIS_ID=$1
  STATUS=$2
  PROGRESS=$3

  JSON_PAYLOAD="{\"id\":\"$DIAGNOSIS_ID\",\"status\":\"$STATUS\",\"progressPercentage\":$PROGRESS}"

  echo "Sending update with payload: $JSON_PAYLOAD"

  echo "testing curl: $(which curl)"

  RESPONSE=$(curl -s -w "HTTPSTATUS:%{http_code}" -X PUT "http://192.168.1.162:8080/data-wipe/v1/diagnosis/update" \
    -H "Content-Type: application/json" \
#    -H "x-sso-token: eyJhbGciOiJIUzI1NiJ9.eyJjVGlkIjoiMzFlN2I0MzctODcyNC00MzQ2LThiZGUtOGYzOGE5NWI0MDg0IiwiZXhwIjoxNzQ0NjU1Mzk5LCJndCI6ImNvbnNvbGUiLCJ2dCI6MCwia2lkIjoiNDY1NCJ9.V9c6g2IbNyGQ2JDX5W-i2jPEUi3V4Lf7QHOl5QX2KAE" \
    -d "$JSON_PAYLOAD")

  BODY=$(echo "$RESPONSE" | sed -e 's/HTTPSTATUS\:.*//g')
  STATUS_CODE=$(echo "$RESPONSE" | tr -d '\n' | sed -e 's/.*HTTPSTATUS://')

  echo "Response Body:"
  echo "$BODY"
  echo "Status Code: $STATUS_CODE"
}



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
    log "$file - File is empty or inaccessible."
  fi
}

# Steps:

step_1_wipe() {
  update_progress "$DIAGNOSIS_ID" "pending" "2"
  log_title "STEP 1: Initial Wipe Pass"
  local start_time=$(date +%s)

  find "$TARGET_DIR" -type f 2>/dev/null | while read -r file; do
    case "$file" in
      "$TARGET_DIR/Android/data"/*|"$TARGET_DIR/Android/obb"/*)
        ;;
      *)
        is_step_three=0
        overwrite_file "$file"
        rm -f "$file"
        ;;
    esac
  done

  local end_time=$(date +%s)
  log "Step 1 completed in $((end_time - start_time)) seconds"
  update_progress "$DIAGNOSIS_ID" "pending" "20"
}

step_2_fill() {
  log_title "STEP 2: Fill Storage with Junk"
  local start_time=$(date +%s)

  local free_mb=$(df "$TARGET_DIR" | awk 'NR==2 {print int($4 / 1024)}')
  local max_files=$((free_mb < 100 ? 1 : free_mb / 100))
  mid_index=$((max_files / 2))
  start_last=$((max_files - 200))
  log "Estimated max filler files: $max_files"

  local i=0
  while true; do
    local file="$TARGET_DIR/$JUNK_FILE_PREFIX_$i.dat"
    dd if=/dev/zero of="$file" bs=1M count=$JUNK_BLOCK_SIZE_MB 2>/dev/null || break
    i=$((i + 1))
  done

  local end_time=$(date +%s)
  log "Step 2 completed in $((end_time - start_time)) seconds"
  log "Filled storage with $i junk files."
  update_progress "$DIAGNOSIS_ID" "pending" "50"
}

step_3_final_pass() {
  log_title "STEP 3: Final Wipe Pass"
  local start_time=$(date +%s)
  local counter=1

  find "$TARGET_DIR" -type f 2>/dev/null | while read -r file; do
    case "$file" in
      "$TARGET_DIR/Android/data"/*|"$TARGET_DIR/Android/obb"/*)
        log "[SKIP] $file"
        ;;
      *)
        is_step_three=1
        overwrite_file "$file"

        if [ "$counter" -eq 1 ]; then
          log "[BENCHMARK] First file overwritten"
        elif [ "$counter" -eq 100 ]; then
          log "[BENCHMARK] First 100 files overwritten"
        elif [ "$counter" -eq "$mid_index" ]; then
          log "[BENCHMARK] Midpoint file reached"
        elif [ "$counter" -eq "$start_last" ]; then
          log "[BENCHMARK] Last 200 files started"
        fi

        counter=$((counter + 1))
        rm -f "$file"
        ;;
    esac
  done

  local end_time=$(date +%s)
  log "Step 3 completed in $((end_time - start_time)) seconds"
  update_progress "$DIAGNOSIS_ID" "completed" "100"
}

main() {
  log_title "Secure Wipe Script Started"
  local script_start=$(date +%s)

  log "Target directory set to: $TARGET_DIR"
  step_1_wipe
  step_2_fill
  step_3_final_pass

  local script_end=$(date +%s)
  log_title "Script Completed"
  log "Total Time Taken: $((script_end - script_start)) seconds"
}

main "$@"
