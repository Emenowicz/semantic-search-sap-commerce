#!/bin/bash
# Smoke check of the semantic search OCC endpoint on the local server (README "OCC endpoint").
# Makes 2 Voyage query calls; on the free tier, wait a minute between runs.
set -uo pipefail
B=${1:-https://localhost:9002/occ/v2/semanticsearch/products/semantic-search}
fail=0
check() { # name, expected status, expected text, curl args...
	local name=$1 status=$2 text=$3; shift 3
	local out code
	out=$(curl -sk -G "$B" "$@" -w '\n%{http_code}')
	code=${out##*$'\n'}
	if [[ $code == "$status" && $out == *"$text"* ]]; then echo "ok   $name"; else echo "FAIL $name: HTTP $code"; echo "$out" | head -5; fail=1; fi
}
check 'blank query is a 400 with a message' 400 'query must not be blank' --data-urlencode 'query= '
check 'part code finds the part' 200 '6204-2RS"' --data-urlencode 'query=6204-2RS' -d lang=en -d pageSize=1
check 'trade word the shop copy never uses' 200 '"code" : "FX-912-M6x20-A' --data-urlencode 'query=Inbusschraube M6 x 20 Edelstahl' -d lang=de -d pageSize=1
exit $fail
