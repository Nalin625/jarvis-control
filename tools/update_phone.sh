#!/usr/bin/env bash
# Update the Jarvis Control app on your phone from this laptop. No tap on the phone.
#
#   tools/update_phone.sh --pair                 one-time: pair with the phone (phone in hand)
#   tools/update_phone.sh --address IP:PORT      save the phone's address (Wireless debugging screen)
#   tools/update_phone.sh                        update now
#   tools/update_phone.sh --queue                keep trying until the phone is reachable, then update
#   tools/update_phone.sh --auto                 quiet single check (for the scheduler)
#
# Finding the phone: on the same Wi-Fi it is found by itself. Anywhere else, it is reached
# through Tailscale at the address saved with --address. Wireless debugging must be ON.
set -euo pipefail
export PATH="$PATH:/usr/local/bin:/usr/bin:/bin"   # the scheduler has a short PATH

REPO="Nalin625/jarvis-control"
WORKFLOW="Build APK"
ARTIFACT="JarvisControl-build"
DIR="${JARVIS_PHONE_DIR:-$HOME/jarvis-phone-update}"
LAST="${JARVIS_LAST_RUN:-$HOME/.jarvis-phone-last-run}"        # build number last installed
ADDR_FILE="${JARVIS_PHONE_ADDR_FILE:-$HOME/.jarvis-phone-address}"
RETRY="${JARVIS_RETRY_SECONDS:-300}"                            # queue: wait between tries
MAX_TRIES="${JARVIS_MAX_TRIES:-288}"                            # queue: 288 x 5 min = 24 h

MODE="now"; PAIR=0; ADDR=""; SAVE_ADDR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --pair) PAIR=1 ;;
    --auto) MODE="auto" ;;
    --queue) MODE="queue" ;;
    --address) SAVE_ADDR="${2:?give IP:PORT after --address}"; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1"; exit 2 ;;
  esac
  shift
done

say() { if [ "$MODE" != "now" ]; then echo "[$(date '+%F %T')] $*"; else echo "$*"; fi; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing '$1'. $2"; exit 1; }; }
need adb "Install it with:  sudo apt install adb"
need gh  "Install GitHub CLI (https://cli.github.com), then run this again."
trap 'adb disconnect >/dev/null 2>&1 || true' EXIT

if [ -n "$SAVE_ADDR" ]; then
  echo "$SAVE_ADDR" > "$ADDR_FILE"
  echo "Saved the phone address: $SAVE_ADDR"
  if [ "$MODE" = "now" ] && [ "$PAIR" = 0 ]; then exit 0; fi
fi

if [ "$PAIR" = 1 ]; then
  echo "On the phone: Settings > Developer options > Wireless debugging > Pair device with pairing code."
  read -r -p "Pairing address (IP:PORT shown on the phone): " PA
  read -r -p "6-digit pairing code: " PC
  adb pair "$PA" "$PC"
  echo "Paired. Next, save the phone's address:  tools/update_phone.sh --address IP:PORT"
  exit 0
fi

# Phone address: found on the Wi-Fi first, otherwise the saved one (works over Tailscale).
find_phone() {
  local found=""
  found=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF; exit}' || true)
  if [ -z "$found" ] && [ -f "$ADDR_FILE" ]; then found=$(cat "$ADDR_FILE"); fi
  echo "$found"
}

# Returns 0 = connected and allowed, 2 = not reachable now, 3 = phone has not allowed this laptop.
connect_phone() {
  local out state
  out=$(adb connect "$1" 2>&1 || true)
  echo "$out" | grep -qi "connected" || return 2
  state=$(adb -s "$1" get-state 2>/dev/null || true)
  [ "$state" = "device" ] || return 3
  return 0
}

# Newest successful build of main, as its run number.
latest_run() {
  gh auth status >/dev/null 2>&1 || {
    if [ "$MODE" != "now" ]; then say "Not logged in to GitHub. Run once: gh auth login"; exit 1; fi
    echo "Log in to GitHub once (choose GitHub.com, then log in in the browser):"; gh auth login
  }
  local run
  run=$(gh run list -R "$REPO" --workflow "$WORKFLOW" --branch main --status success --limit 1 \
          --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
  if [ -z "$run" ] || [ "$run" = "null" ]; then
    say "No successful build on main yet. Check the Actions tab on GitHub, then try again."
    exit 1
  fi
  echo "$run"
}

# Downloads build $1 into $DIR and sets APK to the file.
download_build() {
  say "Downloading build #$1 ..."
  rm -rf "$DIR"; mkdir -p "$DIR"
  gh run download "$1" -R "$REPO" -n "$ARTIFACT" -D "$DIR"
  APK=$(find "$DIR" -name '*.apk' | head -n 1)
  if [ -z "$APK" ]; then
    say "That build has no APK (it probably failed). See its log on GitHub."
    exit 1
  fi
}

# Installs $APK on the connected phone $1, keeping settings and data.
# Returns 0 = installed, 1 = stop (the phone must be fixed by hand first).
install_apk() {
  say "Installing on the phone ..."
  local out
  out=$(adb -s "$1" install -r "$APK" 2>&1 || true)
  echo "$out"
  if echo "$out" | grep -q "Success"; then return 0; fi
  if echo "$out" | grep -q "UPDATE_INCOMPATIBLE\|signatures do not match"; then
    say "The phone's copy was signed with a different key, so it can't be updated in place."
    say "One-time fix: uninstall Jarvis Control on the phone, then run this again (settings are reset)."
  elif echo "$out" | grep -q "USER_RESTRICTED"; then
    say "The phone blocked the install. On Xiaomi/Redmi: Developer options > turn on"
    say "'Install via USB' and 'USB debugging (Security settings)' (needs a Mi account), then run this again."
  else
    say "Install failed. The message above says why."
  fi
  return 1
}

done_msg() {
  echo "$1" > "$LAST"
  say "Done. The phone now runs build #$1."
  command -v notify-send >/dev/null 2>&1 && notify-send "Jarvis" "Phone updated to build #$1" || true
}

case "$MODE" in
  now|auto)
    RUN=$(latest_run)
    if [ "$MODE" = "auto" ] && [ -f "$LAST" ] && [ "$(cat "$LAST")" = "$RUN" ]; then
      say "Phone already has build #$RUN. Nothing to do."; exit 0
    fi
    DEV=$(find_phone)
    if [ -z "$DEV" ]; then
      if [ "$MODE" = "auto" ]; then say "No phone address yet. Nothing to do."; exit 0; fi
      say "No phone address. Be on the phone's Wi-Fi, or save its address:  tools/update_phone.sh --address IP:PORT"
      exit 1
    fi
    rc=0; connect_phone "$DEV" || rc=$?
    if [ "$rc" = 2 ]; then
      if [ "$MODE" = "auto" ]; then say "Phone at $DEV is not reachable. Nothing to do."; exit 0; fi
      say "Can't reach the phone at $DEV. Check Wireless debugging is ON and the phone has network."
      say "To keep trying until it is reachable, use:  tools/update_phone.sh --queue"
      exit 1
    fi
    if [ "$rc" = 3 ]; then say "The phone has not allowed this laptop. Run:  tools/update_phone.sh --pair"; exit 1; fi
    download_build "$RUN"
    install_apk "$DEV" || exit 1
    done_msg "$RUN"
    ;;

  queue)
    RUN=$(latest_run)
    download_build "$RUN"
    say "Build #$RUN is ready. Waiting for the phone (every $((RETRY / 60)) min, up to 24 h) ..."
    tries=0
    while :; do
      tries=$((tries + 1))
      DEV=$(find_phone)
      rc=2
      if [ -n "$DEV" ]; then rc=0; connect_phone "$DEV" || rc=$?; fi
      if [ "$rc" = 0 ]; then
        install_apk "$DEV" || exit 1
        done_msg "$RUN"
        exit 0
      fi
      if [ "$rc" = 3 ]; then say "The phone has not allowed this laptop. Run:  tools/update_phone.sh --pair"; exit 1; fi
      if [ "$tries" -ge "$MAX_TRIES" ]; then say "Gave up after $MAX_TRIES tries. Run the queue again."; exit 1; fi
      say "Phone not reachable yet (try $tries). Trying again later."
      sleep "$RETRY"
    done
    ;;
esac
