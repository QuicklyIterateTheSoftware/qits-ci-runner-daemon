#!/bin/sh
# Offline test of the runner install script qits-ci renders, against stubs, in a temp root.
#
# It runs scripts/fixtures/runner-install.sh — a copy of one rendering of qits-ci-service's
# service/src/main/resources/runner-install.sh.tmpl — twice: a first install, then a rotation (the
# same script with a new registration token, which is what the CI UI's "replace registration token"
# hands out). Nothing it runs reaches a network, a real docker, a real systemd or the real /etc.
#
# THE CONTRACT the template must honour, so this test can run it:
#
#   1. Every absolute path the script writes is prefixed with "${QITS_INSTALL_ROOT:-}" — the binary
#      at $ROOT/usr/local/bin/qits-ci-runner, the env file at $ROOT/etc/qits-ci-runner.env, the unit
#      at $ROOT/etc/systemd/system/qits-ci-runner.service. Unset, the prefix is empty and the paths
#      are the real ones.
#   2. It takes docker, id, useradd, systemctl and curl from PATH — never an absolute path — so the
#      stubs below stand in for them. The root check is `id -u` answering 0.
#   3. The binary lands executable at $ROOT/usr/local/bin/qits-ci-runner, downloaded with curl. When
#      an executable is already there (a rotation), it is kept and curl is not called for it.
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

run_install() { # $1 = script, $2 = log name
  if ! PATH="$STUBS:$PATH" QITS_INSTALL_ROOT="$ROOT" sh "$1" > "$WORK/output-$2.log" 2>&1; then
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
run_install "$FIXTURE" first

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
for key in QITS_CI_RUNNER_URL QITS_CI_RUNNER_ID QITS_CI_RUNNER_REGISTRATION_TOKEN QITS_CI_RUNNER_STATE_DIR QITS_CI_RUNNER_SLOTS; do
  [ -n "$(env_value "$key")" ] || fail "$key is empty in the env file"
done
TOKEN=$(env_value QITS_CI_RUNNER_REGISTRATION_TOKEN)

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

# ---- rotation: the same script with a new token --------------------------------------------------

NEW_TOKEN="qits-reg-ROTATED-$$-DO-NOT-PRINT"
sed "s|$TOKEN|$NEW_TOKEN|g" "$FIXTURE" > "$WORK/rotated.sh"
grep -qF -- "$NEW_TOKEN" "$WORK/rotated.sh" || fail "could not derive a rotated script from the fixture"
: > "$CALLS"
run_install "$WORK/rotated.sh" rotation

grep -q '^curl ' "$CALLS" && fail "a rotation downloaded the binary again instead of keeping it"
[ "$(env_value QITS_CI_RUNNER_REGISTRATION_TOKEN)" = "$NEW_TOKEN" ] || fail "the env file does not carry the new token"
[ "$(mode_of "$ENVFILE")" = "600" ] || fail "the rewritten env file is mode $(mode_of "$ENVFILE")"
grep -qx 'systemctl restart qits-ci-runner' "$CALLS" || fail "a rotation did not restart the unit"
grep -qF -- "$NEW_TOKEN" "$WORK/output-rotation.log" && fail "the new token appeared in the output"
grep -q '^useradd' "$CALLS" && fail "a rotation tried to create the user again"

echo "PASS: install contract — binary, env file (0600, five keys), unit, systemctl, rotation, no token printed."
