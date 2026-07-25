#!/bin/sh
# Runs inside the cluster (as a throwaway pod) against the real
# gateway Service, so traffic actually goes through kube-proxy's
# endpoint routing like a real client - unlike `kubectl port-forward`,
# which pins the whole tunnel to one specific pod and breaks entirely
# the instant that pod terminates, regardless of whether the rolling
# update itself is graceful. That is a test-harness artifact, not
# something AC7 is about.
set -u
N="${STREAM_COUNT:-500}"
OUT_DIR=/tmp/streams
mkdir -p "$OUT_DIR"

i=0
while [ "$i" -lt "$N" ]; do
  (
    curl -sS -N -m 180 -X POST http://gateway:8080/v1/chat/completions \
      -H "Content-Type: application/json" \
      -d "{\"model\":\"mock\",\"messages\":[{\"role\":\"user\",\"content\":\"ac7 stream $i\"}],\"stream\":true}" \
      -o "$OUT_DIR/$i.out" 2>"$OUT_DIR/$i.err"
    echo $? > "$OUT_DIR/$i.rc"
  ) &
  i=$((i + 1))
done

echo "READY_SIGNAL: all $N streams launched"
wait
echo "ALL_STREAMS_FINISHED"

ok=0
broken=0
for f in "$OUT_DIR"/*.out; do
  idx=$(basename "$f" .out)
  rc=$(cat "$OUT_DIR/$idx.rc" 2>/dev/null || echo "?")
  if [ "$rc" = "0" ] && grep -q '^data:\[DONE\]' "$f"; then
    ok=$((ok + 1))
  else
    broken=$((broken + 1))
    echo "BROKEN idx=$idx rc=$rc size=$(wc -c < "$f") err=$(cat "$OUT_DIR/$idx.err" 2>/dev/null | tr '\n' ' ')"
  fi
done

echo "RESULT ok=$ok broken=$broken total=$N"
