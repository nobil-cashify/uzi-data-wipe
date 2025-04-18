#!/system/bin/sh

json_input="$1"

#url="https://data-wipe.api.stage.cashify.in/v1/device/register"
url="http://192.168.1.176:8080/data-wipe/v1/device/register"

response=$(curl -s -X POST "$url" \
    -H "Content-Type: application/json" \
    -d "$json_input")

# Output response to stdout
echo "$response"
