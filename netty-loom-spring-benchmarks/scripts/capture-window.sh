# shellcheck shell=bash
# Sourced by run-all.sh and profile-memory.sh: checks that CAPTURE_AT falls inside k6's hold, which
# RAMP and DURATION set, and sets HOLD_START and HOLD_END. A sample taken in the ramp or the
# ramp-down divides by connections that were not open, so a capture outside the hold is refused
# here and one that runs past it is discarded by discard_past_hold.

for d in "$RAMP" "$DURATION"; do
  [[ "$d" =~ ^[0-9]+s$ ]] || { echo "RAMP and DURATION must be whole seconds, e.g. 60s; got '$d'" >&2; exit 1; }
done
[[ "$CAPTURE_AT" =~ ^[0-9]+$ ]] || { echo "CAPTURE_AT must be whole seconds with no unit, e.g. 45; got '$CAPTURE_AT'" >&2; exit 1; }
HOLD_START=${RAMP%s}
HOLD_END=$(( HOLD_START + ${DURATION%s} ))
if [ "$CAPTURE_AT" -le "$HOLD_START" ] || [ "$CAPTURE_AT" -ge "$HOLD_END" ]; then
  echo "CAPTURE_AT must fall inside the hold, ${HOLD_START}-${HOLD_END}s; got $CAPTURE_AT" >&2; exit 1
fi

# Usage: discard_past_hold <capture_file> <$SECONDS when k6 was launched>
discard_past_hold() {
  if [ $(( SECONDS - $2 )) -ge "$HOLD_END" ]; then
    rm -f "$1"
    echo "  (plateau capture ran past the hold -- discarded; lower CAPTURE_AT)"
  fi
}
