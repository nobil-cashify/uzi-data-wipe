#!/system/bin/sh

UUID=$1
URL="https://data-wipe.api.stage.cashify.in:8443/v1/device/connection/$UUID"

while true; do
    RESPONSE=$(curl -s -X POST "$URL" \
        -H "Content-Type: application/json" \
        --connect-timeout 10 \
        --max-time 15)

    TIMESTAMP=$(date +"%Y-%m-%d %H:%M:%S")

    echo "[$TIMESTAMP] POLLING_RESPONSE: $RESPONSE"

    sleep 5
done
