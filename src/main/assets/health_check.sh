#!/system/bin/sh

echo ""
echo "========== Performing Health Check =========="

HEALTH_URL="https://www.google.com"

# Run curl and capture both body and status code
RESPONSE=$(curl -s -w "HTTPSTATUS:%{http_code}" "$HEALTH_URL")
BODY=$(echo "$RESPONSE" | sed -e 's/HTTPSTATUS\:.*//g')
STATUS=$(echo "$RESPONSE" | tr -d '\n' | sed -e 's/.*HTTPSTATUS://')

echo "Health check response from $HEALTH_URL:"
echo "HTTP Status Code: $STATUS"
#echo "Response Body:"
#echo "$BODY"
