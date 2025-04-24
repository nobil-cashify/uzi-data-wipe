#!/system/bin/sh

json_input="$1"

#url="https://data-wipe.api.stage.cashify.in/v1/device/register"
url="https://data-wipe.api.stage.cashify.in:8443/v1/device/register"

response=$(curl -s -X POST "$url" \
    -H "Content-Type: application/json" \
    -d "$json_input")

# Output response to stdout
echo "$response"
