#!/system/bin/sh

ACCESS_TOKEN=$1
DIAGNOSIS_ID=$2
STATUS=$3
PROGRESS=$4

JSON_PAYLOAD="{\"uuid\":\"$DIAGNOSIS_ID\",\"status\":\"$STATUS\",\"progressPercentage\":$PROGRESS}"

RESPONSE=$(curl -s -w "HTTPSTATUS:%{http_code}" -X PUT "http://192.168.1.176:8080/data-wipe/v1/device/diagnosis" \
  -H "Content-Type: application/json" \
  -H "x-sso-token: $ACCESS_TOKEN" \
  -d "$JSON_PAYLOAD")

BODY=$(echo "$RESPONSE" | sed -e 's/HTTPSTATUS\:.*//g')
STATUS_CODE=$(echo "$RESPONSE" | tr -d '\n' | sed -e 's/.*HTTPSTATUS://')

#  echo "Response Body:"
#  echo "$BODY"
#  echo "Status Code: $STATUS_CODE"