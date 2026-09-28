#!/bin/sh
# Offline test of the runner install script qits-ci renders, against stubs, in a temp root.
#
# It runs scripts/fixtures/runner-install.sh — a copy of one rendering of qits-ci-service's
# service/src/main/resources/runner-install.sh.tmpl, the GENERIC script `GET
# /ci/api/runners/install.sh` serves — the way the CI UI's one install line runs it: piped into `sh`
# with the four per-runner values in its environment. Twice: a first install, then a rotation (the
# same script with a new registration token in the environment, which is what the line the CI UI's
# "replace registration token" hands out does). Nothing it runs reaches a network, a real docker, a
# real systemd or the real /etc.
#
# THE CONTRACT the template must honour, so this test can run it:
#
#   0. It carries no secret and names no runner: QITS_CI_RUNNER_URL, QITS_CI_RUNNER_ID,
#      QITS_CI_RUNNER_REGISTRATION_TOKEN and QITS_CI_RUNNER_SLOTS come from its environment, and
#      one missing is a refusal (nonzero, naming it) before anything is written.
#
#   1. Every absolute path the script writes is prefixed with "${QITS_INSTALL_ROOT:-}" — the binary
#      at $ROOT/usr/local/bin/qits-ci-runner, the env file at $ROOT/etc/qits-ci-runner.env, the unit
#      at $ROOT/etc/systemd/system/qits-ci-runner.service. Unset, the prefix is empty and the paths
#      are the real ones.
#   2. It takes docker, id, useradd, systemctl and curl from PATH — never an absolute path — so the
#      stubs below stand in for them. The root check is `id -u` answering 0.
#   3. The binary lands executable at $ROOT/usr/local/bin/qits-ci-runner, downloaded with curl. A
#      rotation — an executable already there — downloads it again and replaces it with the pinned
#      version; only whether the unit gets restarted afterward depends on it having been there.
#   4. $ROOT/etc/qits-ci-runner.env is mode 0600 and carries exactly five variables, one KEY=value
#      per line: QITS_CI_RUNNER_URL, QITS_CI_RUNNER_ID, QITS_CI_RUNNER_REGISTRATION_TOKEN,
#      QITS_CI_RUNNER_STATE_DIR, QITS_CI_RUNNER_SLOTS. A rotation rewrites it with the new token.
#   5. The unit written is byte-identical to packaging/qits-ci-runner.service.
#   6. systemctl is called with `daemon-reload`, `enable --now qits-ci-runner`, and — so a rotation
#      takes effect on a running unit — `restart qits-ci-runner`.
#   7. The registration token appears in no line the script prints, stdout or stderr.
#
# The script does NOT touch the runner's state directory. Re-registration on rotation is the
# binary's: it keeps a hash of the token that produced its client and registers again when the env
# file carries a different one.
#
# Needs only a POSIX sh and coreutils/busybox (mktemp, stat, grep, sed, sort, cmp).
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$HERE/.." && pwd)
FIXTURE="$REPO_ROOT/scripts/fixtures/runner-install.sh"
UNIT="$REPO_ROOT/packaging/qits-ci-runner.service"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM
ROOT="$WORK/root"
STUBS="$WORK/stubs"
CALLS="$WORK/calls.log"
mkdir -p "$ROOT" "$STUBS"
: > "$CALLS"

fail() {
  echo "FAIL: $*" >&2
  for log in "$WORK"/output-*.log; do
    [ -f "$log" ] || continue
    echo "--- $(basename "$log") ---" >&2
    cat "$log" >&2
  done
  echo "--- stub calls ---" >&2
  cat "$CALLS" >&2
  exit 1
}

# ---- stubs --------------------------------------------------------------------------------------
# Each records its argv (never to stdout) and answers like the real tool would on a healthy host.

cat > "$STUBS/docker" <<STUB
#!/bin/sh
echo "docker \$*" >> "$CALLS"
exit 0
STUB

# id -u answers 0 (root); `id qits-ci-runner` answers "no such user" until useradd has run.
cat > "$STUBS/id" <<STUB
#!/bin/sh
echo "id \$*" >> "$CALLS"
if [ "\${1:-}" = "-u" ]; then echo 0; exit 0; fi
[ -f "$WORK/user-created" ] && { echo "uid=999(qits-ci-runner)"; exit 0; }
echo "id: '\${1:-}': no such user" >&2
exit 1
STUB

cat > "$STUBS/useradd" <<STUB
#!/bin/sh
echo "useradd \$*" >> "$CALLS"
touch "$WORK/user-created"
STUB

cat > "$STUBS/systemctl" <<STUB
#!/bin/sh
echo "systemctl \$*" >> "$CALLS"
exit 0
STUB

# curl writes a stand-in binary to whatever -o names. The url is recorded; nothing is dialled.
cat > "$STUBS/curl" <<STUB
#!/bin/sh
echo "curl \$*" >> "$CALLS"
out=
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) out="\$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ -n "\$out" ] || { echo "curl stub: no -o" >&2; exit 2; }
printf '#!/bin/sh\necho qits-ci-runner-stub\n' > "\$out"
STUB

chmod +x "$STUBS"/*

URL_VALUE="https://ci.qits.example.org"
ID_VALUE="00000000-0000-0000-0000-000000000001"
TOKEN="qits_tok_CONTRACT-$$-DO-NOT-PRINT"
SLOTS_VALUE=2

# As the install line runs it: `curl … | sudo env <the four values> sh` — the script on stdin.
run_install() { # $1 = token, $2 = log name
  if ! PATH="$STUBS:$PATH" QITS_INSTALL_ROOT="$ROOT" env \
      QITS_CI_RUNNER_URL="$URL_VALUE" \
      QITS_CI_RUNNER_ID="$ID_VALUE" \
      QITS_CI_RUNNER_REGISTRATION_TOKEN="$1" \
      QITS_CI_RUNNER_SLOTS="$SLOTS_VALUE" \
      sh < "$FIXTURE" > "$WORK/output-$2.log" 2>&1; then
    fail "the install script exited nonzero ($2)"
  fi
}

env_value() { # $1 = key
  sed -n "s/^$1=//p" "$ROOT/etc/qits-ci-runner.env"
}

mode_of() {
  stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"
}

# ---- first install ------------------------------------------------------------------------------

sh -n "$FIXTURE" || fail "the fixture does not parse"
grep -q 'qits_tok_' "$FIXTURE" && fail "the generic script carries a token"
grep -q '{{' "$FIXTURE" && fail "the generic script has a placeholder nothing filled"

# ---- a missing value is refused, by name, before anything is written ---------------------------

if PATH="$STUBS:$PATH" QITS_INSTALL_ROOT="$ROOT" env -u QITS_CI_RUNNER_ID \
    QITS_CI_RUNNER_URL="$URL_VALUE" QITS_CI_RUNNER_REGISTRATION_TOKEN="$TOKEN" \
    QITS_CI_RUNNER_SLOTS="$SLOTS_VALUE" sh < "$FIXTURE" > "$WORK/output-missing.log" 2>&1; then
  fail "the install script ran with QITS_CI_RUNNER_ID unset"
fi
grep -q 'QITS_CI_RUNNER_ID is not set' "$WORK/output-missing.log" || fail "the refusal does not name QITS_CI_RUNNER_ID"
[ -z "$(ls -A "$ROOT")" ] || fail "a refused install wrote under QITS_INSTALL_ROOT"
[ ! -s "$CALLS" ] || fail "a refused install ran a tool"
rm -f "$WORK/output-missing.log"

# ---- first install ------------------------------------------------------------------------------

run_install "$TOKEN" first

BIN="$ROOT/usr/local/bin/qits-ci-runner"
[ -x "$BIN" ] || fail "the binary is not executable at <root>/usr/local/bin/qits-ci-runner"
[ "$("$BIN")" = "qits-ci-runner-stub" ] || fail "the installed binary is not what curl downloaded"
grep -q '^curl .*/qits-ci-runner' "$CALLS" || fail "curl was not asked for the binary"

ENVFILE="$ROOT/etc/qits-ci-runner.env"
[ -f "$ENVFILE" ] || fail "no <root>/etc/qits-ci-runner.env"
[ "$(mode_of "$ENVFILE")" = "600" ] || fail "the env file is mode $(mode_of "$ENVFILE"), expected 600"
KEYS=$(sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "$ENVFILE" | sort | tr '\n' ' ')
EXPECTED="QITS_CI_RUNNER_ID QITS_CI_RUNNER_REGISTRATION_TOKEN QITS_CI_RUNNER_SLOTS QITS_CI_RUNNER_STATE_DIR QITS_CI_RUNNER_URL "
[ "$KEYS" = "$EXPECTED" ] || fail "the env file carries [$KEYS], expected exactly [$EXPECTED]"
LINES=$(grep -cv '^[[:space:]]*$' "$ENVFILE")
[ "$LINES" = 5 ] || fail "the env file has $LINES non-empty lines, expected 5"
[ "$(env_value QITS_CI_RUNNER_URL)" = "$URL_VALUE" ] || fail "QITS_CI_RUNNER_URL is not the value it was given"
[ "$(env_value QITS_CI_RUNNER_ID)" = "$ID_VALUE" ] || fail "QITS_CI_RUNNER_ID is not the value it was given"
[ "$(env_value QITS_CI_RUNNER_REGISTRATION_TOKEN)" = "$TOKEN" ] || fail "QITS_CI_RUNNER_REGISTRATION_TOKEN is not the value it was given"
[ "$(env_value QITS_CI_RUNNER_STATE_DIR)" = "/var/lib/qits-ci-runner" ] || fail "QITS_CI_RUNNER_STATE_DIR is not /var/lib/qits-ci-runner"
[ "$(env_value QITS_CI_RUNNER_SLOTS)" = "$SLOTS_VALUE" ] || fail "QITS_CI_RUNNER_SLOTS is not the value it was given"
grep -q "^curl .*Authorization: Bearer $TOKEN" "$CALLS" || fail "the binary was not downloaded with the registration token as its bearer"

UNIT_OUT="$ROOT/etc/systemd/system/qits-ci-runner.service"
[ -f "$UNIT_OUT" ] || fail "no unit at <root>/etc/systemd/system/qits-ci-runner.service"
cmp -s "$UNIT" "$UNIT_OUT" || fail "the installed unit differs from packaging/qits-ci-runner.service"

grep -qx 'systemctl daemon-reload' "$CALLS" || fail "systemctl daemon-reload was not called"
grep -qx 'systemctl enable --now qits-ci-runner' "$CALLS" || fail "systemctl enable --now qits-ci-runner was not called"
grep -q '^useradd .*qits-ci-runner' "$CALLS" || fail "the system user was not created"
grep -q '^id -u' "$CALLS" || fail "the root check did not ask id -u"

grep -qF -- "$TOKEN" "$WORK/output-first.log" && fail "the registration token appeared in the install script's output"

# The root prefix held: nothing escaped to the real filesystem's view of $ROOT's siblings.
[ ! -e "$WORK/usr" ] && [ ! -e "$WORK/etc" ] || fail "something was written outside QITS_INSTALL_ROOT"

# ---- rotation: the same script, a new token in its environment -----------------------------------

NEW_TOKEN="qits_tok_ROTATED-$$-DO-NOT-PRINT"
: > "$CALLS"
run_install "$NEW_TOKEN" rotation

grep -q '^curl .*/qits-ci-runner' "$CALLS" || fail "a rotation did not download the binary again"
grep -q "^curl .*Authorization: Bearer $NEW_TOKEN" "$CALLS" || fail "a rotation did not download the binary with the new registration token as its bearer"
[ -x "$BIN" ] || fail "the binary is no longer executable after a rotation"
[ "$("$BIN")" = "qits-ci-runner-stub" ] || fail "the rotated binary is not what curl downloaded"
[ "$(env_value QITS_CI_RUNNER_REGISTRATION_TOKEN)" = "$NEW_TOKEN" ] || fail "the env file does not carry the new token"
[ "$(mode_of "$ENVFILE")" = "600" ] || fail "the rewritten env file is mode $(mode_of "$ENVFILE")"
grep -qx 'systemctl restart qits-ci-runner' "$CALLS" || fail "a rotation did not restart the unit"
grep -qF -- "$NEW_TOKEN" "$WORK/output-rotation.log" && fail "the new token appeared in the output"
grep -q '^useradd' "$CALLS" && fail "a rotation tried to create the user again"

echo "PASS: install contract — values from the environment, binary, env file (0600, five keys), unit, systemctl, rotation, no token printed."
