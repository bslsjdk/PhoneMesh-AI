#!/data/data/com.termux/files/usr/bin/bash
# Termux launcher with a best-effort 4 GiB RSS watchdog.
# Usage:
#   bash scripts/termux-run.sh smoke MODEL.gguf
#   bash scripts/termux-run.sh node MODEL.gguf WORLD RANK MASTER_IP NEXT_IP [PROMPT]
set -u

ENGINE="${HOME}/prima.cpp/llama-cli"
MODE="${1:-}"
MODEL="${2:-}"
LIMIT_KIB=$((4 * 1024 * 1024))
POLL_SECONDS=1

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }

[ -x "$ENGINE" ] || fail "Engine not found/executable: $ENGINE (build prima.cpp first)."
[ -n "$MODEL" ] || fail "Missing model path."
[ -f "$MODEL" ] || fail "Model file does not exist: $MODEL"
command -v awk >/dev/null 2>&1 || fail "awk is required."
command -v sleep >/dev/null 2>&1 || fail "sleep is required."

printf 'Engine: %s\nModel: %s\n' "$ENGINE" "$MODEL"
ls -lh "$MODEL"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$MODEL"
fi
printf 'RSS watchdog limit: 4 GiB (best effort; Android may account memory differently).\n'

case "$MODE" in
  smoke)
    shift 2
    set -- "$ENGINE" -m "$MODEL" -c 256 -n 8 -p "Reply in one short sentence: what is Wi-Fi?"
    ;;
  node)
    WORLD="${3:-}"
    RANK="${4:-}"
    MASTER="${5:-}"
    NEXT="${6:-}"
    PROMPT="${7:-}"
    case "$WORLD" in 2|3) ;; *) fail "WORLD must be 2 or 3 for this staged test." ;; esac
    case "$RANK" in 0|1|2) ;; *) fail "RANK must be 0, 1, or 2." ;; esac
    [ "$RANK" -lt "$WORLD" ] || fail "RANK must be less than WORLD."
    [ -n "$MASTER" ] || fail "Missing MASTER_IP."
    [ -n "$NEXT" ] || fail "Missing NEXT_IP."
    shift 6
    if [ "$RANK" -eq 0 ]; then
      [ -n "$PROMPT" ] || PROMPT="Explain briefly how phones can cooperate on a computation."
      set -- "$ENGINE" -m "$MODEL" -c 256 -n 16 -p "$PROMPT" --world "$WORLD" --rank "$RANK" --master "$MASTER" --next "$NEXT" --prefetch
    else
      set -- "$ENGINE" -m "$MODEL" --world "$WORLD" --rank "$RANK" --master "$MASTER" --next "$NEXT" --prefetch
    fi
    ;;
  *)
    fail "Usage: $0 smoke MODEL.gguf | node MODEL.gguf WORLD RANK MASTER_IP NEXT_IP [PROMPT]"
    ;;
esac

"$@" &
PID=$!
printf 'Started inference PID=%s\n' "$PID"
PEAK_KIB=0
TRIPPED=0
while kill -0 "$PID" 2>/dev/null; do
  RSS_KIB=$(awk '/^VmRSS:/ {print $2; found=1} END {if (!found) print 0}' "/proc/$PID/status" 2>/dev/null || printf '0')
  case "$RSS_KIB" in ''|*[!0-9]*) RSS_KIB=0 ;; esac
  if [ "$RSS_KIB" -gt "$PEAK_KIB" ]; then PEAK_KIB=$RSS_KIB; fi
  if [ "$RSS_KIB" -gt "$LIMIT_KIB" ]; then
    printf 'MEMORY LIMIT EXCEEDED: RSS=%s KiB (>4 GiB). Stopping PID %s.\n' "$RSS_KIB" "$PID" >&2
    kill "$PID" 2>/dev/null || true
    TRIPPED=1
    break
  fi
  sleep "$POLL_SECONDS"
done
wait "$PID"
STATUS=$?
printf 'Observed peak RSS: %s KiB (%.2f GiB)\n' "$PEAK_KIB" "$(awk -v k="$PEAK_KIB" 'BEGIN {printf k/1048576}')"
if [ "$TRIPPED" -eq 1 ]; then
  fail "Stopped at the 4 GiB RSS watchdog. This is a best-effort guard, not a substitute for Android system memory monitoring."
fi
exit "$STATUS"
