#!/system/bin/sh

UUID=$1
TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJjVGlkIjoiZTlmMGExNzMtYzBmMS00ZTljLThiNGItZDYzZjEwOTQzZWQ4IiwiZXhwIjoxNzQ0ODI4MTk5LCJndCI6ImNvbnNvbGUiLCJ2dCI6MCwia2lkIjoiNDY1NyJ9.S1rvZlPIyda_9LvJOYtVFQrSdj5BYvVVhiTVq9VaPDY"

URL="https://data-wipe.api.stage.cashify.in/v1/device/connection/$UUID"

while true; do
    RESPONSE=$(curl -s -X POST "$URL" \
        -H "x-sso-token: $TOKEN" \
        -H "Content-Type: application/json" \
        --connect-timeout 10 \
        --max-time 15)

    TIMESTAMP=$(date +"%Y-%m-%d %H:%M:%S")

    echo "[$TIMESTAMP] POLLING_RESPONSE: $RESPONSE"

    sleep 5
done
