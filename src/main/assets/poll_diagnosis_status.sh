#!/system/bin/sh


DIR=$(dirname "$0")
. "$DIR/logger.sh"

log "Current directory: $DIR"
log_title "Diagnosis Polling Started"

UUID=$1
URL="https://data-wipe.api.stage.cashify.in:8443/v1/device/diagnose/$UUID"
WIPE_SCRIPT_PATH="$DIR/wipe_script.sh"

while true; do
  RESPONSE=$(curl -s -X GET "$URL" \
    -H "Content-Type: application/json" \
    --connect-timeout 10 \
    --max-time 15)

  log "DIAGNOSIS POLLING RESPONSE: $RESPONSE"

  STATUS=$(echo "$RESPONSE" | grep -o '"status":"[^"]*"' | cut -d':' -f2 | tr -d '"')
  SSO_TOKEN=$(echo "$RESPONSE" | grep -o '"ssoToken":"[^"]*"' | cut -d':' -f2 | tr -d '"')

  if [ "$STATUS" = "INITIATED" ]; then
    log "Status is INITIATED. Executing wipe_script.sh"
    break
  fi

  sleep 5
done

sh "$WIPE_SCRIPT_PATH" "$UUID" "$SSO_TOKEN"
