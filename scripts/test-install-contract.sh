#!/bin/sh
# Offline test of the runner install contract, against a stub docker.
#
# It runs scripts/fixtures/runner-install.sh — the REFERENCE IMPLEMENTATION the template qits-ci
# renders `GET /ci/api/runners/install.sh` from is written to match — the way the CI UI's one install
# line runs it: piped into `sh` with the four per-runner values in its environment. A first install,
# then a re-run with a new token (the CI UI's "replace registration token", which is also how a
# runner is replaced). Nothing it runs reaches a network or a real docker.
#
# THE CONTRACT:
#
#   0. It carries no secret and names no runner: QITS_CI_RUNNER_URL, QITS_CI_RUNNER_ID,
#      QITS_CI_RUNNER_REGISTRATION_TOKEN and QITS_CI_RUNNER_SLOTS come from its environment, and one
#      missing is a refusal (nonzero, naming it) before docker is asked anything. The IMAGE and its
#      version are the two values rendered into it.
#   1. It needs docker, and asks: `docker version` must answer, or it refuses and runs nothing else.
#      docker comes from PATH, so the stub below stands in for it.
#   2. It logs in to the image's registry as `docker --config <tmp> login <registry host> -u token
#      --password-stdin`, the registration token on stdin, in a 0700 directory of its own; pulls the
#      image under that same --config; and deletes the directory. A failed login or pull is a
#      refusal that leaves whatever runner is installed alone.
#   3. It removes (`docker rm -f`) every container `docker ps -aq --filter
#      label=qits.ci.runner.process=<id>` lists — and never selects by the step label qits.ci.runner.
#   4. It keeps the state volume qits-ci-runner-state-<first 8 of id> but removes client.json from it,
#      with a throwaway container of the image, so the token in the line is the one that registers.
#   5. It starts the runner with exactly the container contract's `docker run` (asserted element for
#      element below): name qits-ci-runner-<id8>-<version>, --restart unless-stopped, the process and
#      version labels, the socket and the state volume, no --network, the three values as -e K=V and
#      the token as `-e QITS_CI_RUNNER_REGISTRATION_TOKEN`, then the optional tuning variables that
#      are set, then the image.
#   6. It prints `qits-ci-runner started; watch: docker logs -f <name>`.
#   7. The registration token appears in no line it prints and in no docker argv; it reaches docker
#      only on the login's stdin and in the environment of the `run`.
#
# Needs only a POSIX sh and coreutils/busybox (mktemp, stat, grep, sed, cut, printenv).
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$HERE/.." && pwd)
FIXTURE="$REPO_ROOT/scripts/fixtures/runner-install.sh"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM
STUBS="$WORK/stubs"
CALLS="$WORK/calls.log"
mkdir -p "$STUBS"
: > "$CALLS"

fail() {
  echo "FAIL: $*" >&2
  for log in "$WORK"/output-*.log; do
    [ -f "$log" ] || continue
    echo "--- $(basename "$log") ---" >&2
    cat "$log" >&2
  done
  echo "--- docker calls ---" >&2
  cat "$CALLS" >&2
  exit 1
}

# ---- the stub docker ----------------------------------------------------------------------------
# Records every argv (one line per call, never to stdout), the --config directory each call was
# given and that directory's mode, the login's stdin, and the detached run's argv (one element per
# line) and QITS_CI_RUNNER_* environment. Answers `ps` from $WORK/ps-answer, and fails `version` or
# `pull` when $WORK/version-code or $WORK/pull-code says so.

printf '#!/bin/sh\nW=%s\n' "$WORK" > "$STUBS/docker"
cat >> "$STUBS/docker" <<'STUB'
printf 'docker %s\n' "$*" >> "$W/calls.log"
if [ "$1" = "--config" ]; then
  printf '%s\n' "$2" >> "$W/config-dirs"
  stat -c %a "$2" >> "$W/config-modes" 2>/dev/null || stat -f %Lp "$2" >> "$W/config-modes"
  shift 2
fi
case "$1" in
  version) exit "$(cat "$W/version-code" 2>/dev/null || echo 0)" ;;
  login) cat > "$W/login-stdin" ;;
  pull) exit "$(cat "$W/pull-code" 2>/dev/null || echo 0)" ;;
  ps) cat "$W/ps-answer" 2>/dev/null || true ;;
  run)
    if [ "$2" = "-d" ]; then
      : > "$W/run-argv"
      for a in "$@"; do printf '%s\n' "$a" >> "$W/run-argv"; done
      env | grep '^QITS_CI_RUNNER_' | sort > "$W/run-env"
      echo 4f6c1e0b2a9d
    fi
    ;;
esac
exit 0
STUB
chmod +x "$STUBS/docker"

URL_VALUE="https://ci.qits.example.org"
ID_VALUE="3f2a9c1e-0000-4000-8000-000000000001"
ID8="3f2a9c1e"
TOKEN="qits_tok_CONTRACT-$$-DO-NOT-PRINT"
SLOTS_VALUE=2
IMAGE="registry.qits.example.org/qits/qits-ci-runner:0.0.0-fixture"
NAME="qits-ci-runner-$ID8-0.0.0-fixture"

reset_stub() {
  : > "$CALLS"
  rm -f "$WORK/config-dirs" "$WORK/config-modes" "$WORK/login-stdin" "$WORK/run-argv" \
    "$WORK/run-env" "$WORK/version-code" "$WORK/pull-code" "$WORK/ps-answer"
}

# As the install line runs it: `curl … | sudo env <the four values> sh` — the script on stdin. Extra
# NAME=value arguments ride along in the same environment.
install() { # $1 = token, $2 = log name, $3… = extra environment
  token=$1 log=$2
  shift 2
  PATH="$STUBS:$PATH" env \
    QITS_CI_RUNNER_URL="$URL_VALUE" \
    QITS_CI_RUNNER_ID="$ID_VALUE" \
    QITS_CI_RUNNER_REGISTRATION_TOKEN="$token" \
    QITS_CI_RUNNER_SLOTS="$SLOTS_VALUE" \
    "$@" \
    sh < "$FIXTURE" > "$WORK/output-$log.log" 2>&1
}

called() { grep -qxF -- "docker $*" "$CALLS"; }

# ---- the generic script -------------------------------------------------------------------------

sh -n "$FIXTURE" || fail "the fixture does not parse"
grep -q 'qits_tok_' "$FIXTURE" && fail "the generic script carries a token"
grep -q '{{' "$FIXTURE" && fail "the generic script has a placeholder nothing filled"
grep -qi 'systemctl\|systemd' "$FIXTURE" && fail "the script still speaks systemd"

# ---- a missing value is refused, by name, before docker is asked anything -----------------------

reset_stub
if PATH="$STUBS:$PATH" env -u QITS_CI_RUNNER_ID \
    QITS_CI_RUNNER_URL="$URL_VALUE" QITS_CI_RUNNER_REGISTRATION_TOKEN="$TOKEN" \
    QITS_CI_RUNNER_SLOTS="$SLOTS_VALUE" sh < "$FIXTURE" > "$WORK/output-missing.log" 2>&1; then
  fail "the install script ran with QITS_CI_RUNNER_ID unset"
fi
grep -q 'QITS_CI_RUNNER_ID is not set' "$WORK/output-missing.log" || fail "the refusal does not name QITS_CI_RUNNER_ID"
[ ! -s "$CALLS" ] || fail "a refused install ran docker"
rm -f "$WORK/output-missing.log"

# ---- a docker that does not answer is refused, and nothing else runs ------------------------------

reset_stub
echo 1 > "$WORK/version-code"
install "$TOKEN" nodocker && fail "the install script ran against a docker that does not answer"
grep -q 'docker does not answer' "$WORK/output-nodocker.log" || fail "the refusal does not say docker does not answer"
[ "$(wc -l < "$CALLS" | tr -d ' ')" = 1 ] && called version || fail "a docker that does not answer was asked more than its version"
rm -f "$WORK/output-nodocker.log"

# ---- a pull that fails leaves the installed runner alone ------------------------------------------

reset_stub
echo 1 > "$WORK/pull-code"
printf 'aaaaaaaaaaaa\n' > "$WORK/ps-answer"
install "$TOKEN" nopull && fail "the install script carried on after a failed pull"
grep -q "could not pull $IMAGE" "$WORK/output-nopull.log" || fail "the refusal does not name the image"
grep -q '^docker rm\|^docker run\|^docker ps' "$CALLS" && fail "a failed pull still removed or started a container"
while read -r dir; do [ ! -e "$dir" ] || fail "the login directory $dir outlived a failed pull"; done < "$WORK/config-dirs"
rm -f "$WORK/output-nopull.log"

# ---- first install -------------------------------------------------------------------------------

reset_stub
install "$TOKEN" first || fail "the install script exited nonzero (first)"

called version || fail "docker version was not asked"

# 2. login and pull, under one throwaway config, deleted afterwards.
CONFIG=$(head -1 "$WORK/config-dirs")
[ -n "$CONFIG" ] || fail "no docker call was given --config"
[ "$(sort -u "$WORK/config-dirs" | wc -l | tr -d ' ')" = 1 ] || fail "the login and the pull used different --config directories"
called --config "$CONFIG" login registry.qits.example.org -u token --password-stdin \
  || fail "the login is not 'docker --config <tmp> login registry.qits.example.org -u token --password-stdin'"
[ "$(cat "$WORK/login-stdin")" = "$TOKEN" ] || fail "the login did not read the registration token on stdin"
called --config "$CONFIG" pull "$IMAGE" || fail "the image was not pulled under the login's --config"
[ "$(sort -u "$WORK/config-modes")" = 700 ] || fail "the login directory was mode $(sort -u "$WORK/config-modes" | tr '\n' ' '), expected 700"
[ ! -e "$CONFIG" ] || fail "the login directory $CONFIG was not deleted"
[ "$(grep -n ' login ' "$CALLS" | cut -d: -f1)" -lt "$(grep -n ' pull ' "$CALLS" | cut -d: -f1)" ] || fail "the pull ran before the login"

# 3. the runner's containers, by the process label, never the step label.
called ps -aq --filter "label=qits.ci.runner.process=$ID_VALUE" || fail "the runner's containers were not listed by qits.ci.runner.process"
grep -q 'label=qits.ci.runner=' "$CALLS" && fail "the script selected by the step label qits.ci.runner"

# 4. the client goes, the volume stays.
called run --rm --entrypoint rm -v "qits-ci-runner-state-$ID8:/var/lib/qits-ci-runner" "$IMAGE" -f /var/lib/qits-ci-runner/client.json \
  || fail "client.json was not removed from the state volume"
grep -q '^docker volume rm' "$CALLS" && fail "the state volume was removed"

# 5. the container contract, element for element.
EXPECTED_RUN="$WORK/expected-run"
printf '%s\n' run -d \
  --name "$NAME" \
  --restart unless-stopped \
  --label "qits.ci.runner.process=$ID_VALUE" \
  --label "qits.ci.runner.version=0.0.0-fixture" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "qits-ci-runner-state-$ID8:/var/lib/qits-ci-runner" \
  -e "QITS_CI_RUNNER_URL=$URL_VALUE" \
  -e "QITS_CI_RUNNER_ID=$ID_VALUE" \
  -e "QITS_CI_RUNNER_SLOTS=$SLOTS_VALUE" \
  -e QITS_CI_RUNNER_REGISTRATION_TOKEN \
  "$IMAGE" > "$EXPECTED_RUN"
[ -f "$WORK/run-argv" ] || fail "the runner container was not started with docker run -d"
cmp -s "$EXPECTED_RUN" "$WORK/run-argv" || {
  echo "--- expected run argv ---" >&2; cat "$EXPECTED_RUN" >&2
  echo "--- actual run argv ---" >&2; cat "$WORK/run-argv" >&2
  fail "the runner's docker run is not the container contract"
}
grep -qxF "QITS_CI_RUNNER_REGISTRATION_TOKEN=$TOKEN" "$WORK/run-env" || fail "docker run did not have the token in its environment for -e QITS_CI_RUNNER_REGISTRATION_TOKEN"
[ "$(grep -n ' pull ' "$CALLS" | cut -d: -f1)" -lt "$(grep -n '^docker run -d' "$CALLS" | cut -d: -f1)" ] || fail "the runner started before the image was pulled"

# 6. the one line.
[ "$(tail -1 "$WORK/output-first.log")" = "qits-ci-runner started; watch: docker logs -f $NAME" ] \
  || fail "the last line is not 'qits-ci-runner started; watch: docker logs -f $NAME'"

# 7. the token: not printed, not in any argv.
grep -qF -- "$TOKEN" "$WORK/output-first.log" && fail "the registration token appeared in the install script's output"
grep -qF -- "$TOKEN" "$CALLS" && fail "the registration token appeared in a docker argv"

# ---- a re-run: a new token, a runner already there, an operator's tuning ------------------------

NEW_TOKEN="qits_tok_ROTATED-$$-DO-NOT-PRINT"
reset_stub
printf 'aaaaaaaaaaaa\nbbbbbbbbbbbb\n' > "$WORK/ps-answer"
install "$NEW_TOKEN" rerun \
  QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES=dev-qits-artifacts:8080,dev-qits-platform-mirror:8080 \
  || fail "the install script exited nonzero (re-run)"

called rm -f aaaaaaaaaaaa && called rm -f bbbbbbbbbbbb || fail "a re-run did not remove the runner containers already there"
[ "$(grep -n '^docker rm -f bbbbbbbbbbbb' "$CALLS" | cut -d: -f1)" -lt "$(grep -n '^docker run -d' "$CALLS" | cut -d: -f1)" ] || fail "the old runner was removed after the new one started"
[ "$(cat "$WORK/login-stdin")" = "$NEW_TOKEN" ] || fail "a re-run did not log in with the new token"
grep -qxF "QITS_CI_RUNNER_REGISTRATION_TOKEN=$NEW_TOKEN" "$WORK/run-env" || fail "a re-run did not start the runner with the new token"
called run --rm --entrypoint rm -v "qits-ci-runner-state-$ID8:/var/lib/qits-ci-runner" "$IMAGE" -f /var/lib/qits-ci-runner/client.json \
  || fail "a re-run did not remove client.json, so the new token would never register"
grep -q '^docker volume rm' "$CALLS" && fail "a re-run removed the state volume"
tail -2 "$WORK/run-argv" | head -1 | grep -qxF 'QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES=dev-qits-artifacts:8080,dev-qits-platform-mirror:8080' \
  || fail "a tuning variable that was set did not reach the container"
grep -q 'QITS_CI_RUNNER_ROLLOVER_TIMEOUT\|QITS_CI_RUNNER_DOCKER_TIMEOUT' "$WORK/run-argv" && fail "a tuning variable that was not set reached the container"
grep -qF -- "$NEW_TOKEN" "$WORK/output-rerun.log" && fail "the new token appeared in the output"
grep -qF -- "$NEW_TOKEN" "$CALLS" && fail "the new token appeared in a docker argv"

echo "PASS: install contract — values from the environment, docker version, throwaway login + pull, replace by process label, client.json reset on a kept volume, the container contract's docker run, no token printed or in an argv."
