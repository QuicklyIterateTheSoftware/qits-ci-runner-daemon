#!/bin/sh
# PLACEHOLDER FIXTURE — a hand-written stand-in for what qits-ci-service renders from
# service/src/main/resources/runner-install.sh.tmpl, until that template exists and this file is
# refreshed from one real rendering of it. It follows the contract scripts/test-install-contract.sh
# states in its header (and README.md repeats), and nothing more: the test runs whatever file sits
# here, so the day the real rendering replaces this one, the contract is checked against the real
# thing.
#
# Rendered values: a real rendering carries the runner's own; these are fixture values.
set -eu

QITS_CI_RUNNER_URL='https://ci.dev.example.test'
QITS_CI_RUNNER_ID='runner-fixture-1'
QITS_CI_RUNNER_REGISTRATION_TOKEN='qits-reg-FIXTURE-TOKEN-DO-NOT-PRINT'
QITS_CI_RUNNER_SLOTS='2'
QITS_CI_RUNNER_BINARY_URL='https://artifacts.dev.example.test/artifacts/daemons/qits-ci-runner/1.0.0-SNAPSHOT'

root="${QITS_INSTALL_ROOT:-}"
bin="$root/usr/local/bin/qits-ci-runner"
envfile="$root/etc/qits-ci-runner.env"
unit="$root/etc/systemd/system/qits-ci-runner.service"
state_dir='/var/lib/qits-ci-runner'

fail() { echo "qits-ci-runner install: $*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || fail "run this as root (sudo sh, or paste it into a root shell)"
command -v docker >/dev/null 2>&1 || fail "docker is not installed on this host"
docker info >/dev/null 2>&1 || fail "docker is installed but not answering; start it first"
command -v systemctl >/dev/null 2>&1 || fail "this host has no systemd"

if ! id qits-ci-runner >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin --groups docker qits-ci-runner
  echo "created the system user qits-ci-runner (in group docker)"
fi

if [ -x "$bin" ]; then
  echo "qits-ci-runner is already installed at /usr/local/bin/qits-ci-runner; keeping it"
else
  mkdir -p "$root/usr/local/bin"
  curl -fsSL --retry 3 -o "$bin.tmp" "$QITS_CI_RUNNER_BINARY_URL" || fail "could not download the runner"
  chmod 0755 "$bin.tmp"
  mv "$bin.tmp" "$bin"
  echo "installed /usr/local/bin/qits-ci-runner"
fi

mkdir -p "$root/etc"
( umask 077
  printf '%s\n' \
    "QITS_CI_RUNNER_URL=$QITS_CI_RUNNER_URL" \
    "QITS_CI_RUNNER_ID=$QITS_CI_RUNNER_ID" \
    "QITS_CI_RUNNER_REGISTRATION_TOKEN=$QITS_CI_RUNNER_REGISTRATION_TOKEN" \
    "QITS_CI_RUNNER_STATE_DIR=$state_dir" \
    "QITS_CI_RUNNER_SLOTS=$QITS_CI_RUNNER_SLOTS" > "$envfile.tmp" )
chmod 0600 "$envfile.tmp"
mv "$envfile.tmp" "$envfile"
echo "wrote /etc/qits-ci-runner.env (mode 0600)"

mkdir -p "$root/etc/systemd/system"
cat > "$unit" <<'QITS_UNIT'
# qits-ci-runner — the systemd unit the install script writes to /etc/systemd/system.
#
# CHANGE IT TOGETHER WITH qits-ci-service's service/src/main/resources/runner-install.sh.tmpl, which
# embeds this file verbatim. scripts/test-install-contract.sh asserts the installed unit is
# byte-identical to this one, so a drift between the two fails this repository's gate the next time
# the fixture is refreshed from the template.
[Unit]
Description=qits CI runner
# Docker must be up before the runner's boot sweep asks it anything, and the network before it
# dials; `Wants=` so a missing target does not stop the runner from starting at all.
After=docker.service network-online.target
Wants=docker.service network-online.target

[Service]
# The five values the install script wrote, mode 0600: the registration token is among them.
EnvironmentFile=/etc/qits-ci-runner.env
ExecStart=/usr/local/bin/qits-ci-runner
# The runner never exits healthy. Exit 2 and 5 are an env file to fix and will repeat every
# RestartSec until it is; 3 is the platform being away and the restart is the retry.
Restart=always
RestartSec=5
# An unprivileged system user in the docker group — root-equivalent through the socket, which is the
# price of starting containers at all, but not root in its own right.
User=qits-ci-runner
# /var/lib/qits-ci-runner, created and owned for the user above: where client.json lives.
StateDirectory=qits-ci-runner
StateDirectoryMode=0700

[Install]
WantedBy=multi-user.target
QITS_UNIT
echo "wrote /etc/systemd/system/qits-ci-runner.service"

systemctl daemon-reload
systemctl enable --now qits-ci-runner
# enable --now starts a stopped unit and leaves a running one alone; a rotation has just rewritten
# the env file under a running one, so it is restarted to read it.
systemctl restart qits-ci-runner
echo "qits-ci-runner is running. Follow it with: journalctl -u qits-ci-runner -f"
