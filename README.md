# qits-ci-runner-daemon

The **qits CI runner**: a process you install on any Linux machine with docker so that machine runs
CI steps for the platform. It registers once, holds one outbound connection to qits-ci, reserves
runs when it has a free slot, and starts and removes each step's container with the host's docker.
qits-ci still drives every run — it decides the steps, reads their output and records the verdict;
the runner only starts the containers it is told to start and removes the ones it is told to remove.

    ./mvnw verify                           # a clone of this repo alone builds and tests green
    sh scripts/test-install-contract.sh     # the install script's contract, offline

## Installing a runner (for the person at the machine)

### What you need

- **Linux on x86-64**, with **systemd**.
- **Docker**, installed and running (`docker info` answers).
- **Outbound HTTPS** to the platform's domain (the CI service, its identity provider and its
  artifact store). **No inbound port** is ever opened: the runner only dials out, so a VM behind a
  home router or a NAT works as it is.
- **sudo** (or a root shell) for the install.

### The one action

1. In the CI UI, open **Runners**, create a runner (or pick an existing one) and open its install
   panel.
2. **Copy the script** the panel shows and **paste it into a root shell** on the machine.

That is the whole install. The script downloads the `qits-ci-runner` binary to
`/usr/local/bin/qits-ci-runner`, creates the system user `qits-ci-runner` in the `docker` group,
writes `/etc/qits-ci-runner.env` (mode 0600 — it holds the one-time registration token), writes the
`qits-ci-runner` systemd unit, and starts it.

### What a good first start looks like

    journalctl -u qits-ci-runner -f

shows, within a few seconds:

    ci-runner registered as <runner id>
    ci-runner connected slots=2

`registered` appears once, on the very first start: the runner exchanged its registration token for
its own credentials and stored them in `/var/lib/qits-ci-runner/client.json` (mode 0600). The token is
never used again. `connected slots=2` appears on every connection, with the number of runs the CI
lets this runner hold at once — the CI's setting for the runner wins over the `QITS_CI_RUNNER_SLOTS`
the machine advertises. After that you will see `took run …`, `launched run … step …` and `released
run …` as work arrives.

### Rotating the registration

In the CI UI, **Replace registration token** on the runner, then paste the new install script into a
root shell exactly as the first time. It finds the binary already installed and keeps it, rewrites
only `/etc/qits-ci-runner.env` with the new token, and restarts the unit; the runner sees a token it
has not registered with and registers again, replacing its stored credentials.

### Removing a runner

    sudo systemctl disable --now qits-ci-runner

then **delete the runner in the CI UI**, which decommissions its credentials so the stored client can
never connect again. To clean the machine as well, remove `/usr/local/bin/qits-ci-runner`,
`/etc/qits-ci-runner.env`, `/etc/systemd/system/qits-ci-runner.service` and
`/var/lib/qits-ci-runner`, and `docker rm -f qits-ci-runner-buildkitd` if the runner ever built images.

### Environment

The install script writes the first five into `/etc/qits-ci-runner.env`. The rest are for an operator
with a reason.

| Variable | Meaning | Default |
|---|---|---|
| `QITS_CI_RUNNER_URL` | The CI service's base url, e.g. `https://ci.dev.example.eu` or `http://dev-qits-ci:8080`. | required |
| `QITS_CI_RUNNER_ID` | The runner id the CI minted for this runner. | required |
| `QITS_CI_RUNNER_REGISTRATION_TOKEN` | One-time registration token. Needed only until registered; a *different* token later means "register again". | required until registered |
| `QITS_CI_RUNNER_STATE_DIR` | Where `client.json` lives. | `/var/lib/qits-ci-runner` |
| `QITS_CI_RUNNER_SLOTS` | How many runs this machine is set up for. Advertised; the CI's number is the cap. | `1` |
| `QITS_CI_RUNNER_DOCKER_BINARY` | The docker CLI to run. | `docker` |
| `QITS_CI_RUNNER_DOCKER_TIMEOUT` | Seconds any one docker call may take before it is killed. | `120` |
| `QITS_CI_RUNNER_BUILDKIT_IMAGE` | The image of the runner's own buildkitd. | `moby/buildkit:v0.33.0` |

### Exit codes

A healthy runner never exits; systemd restarts it after any of these (`RestartSec=5`).

| Code | Meaning | What to do |
|---|---|---|
| 2 | A variable is missing or unparseable (the journal line names it), or the runner is not registered and has no token. | Fix `/etc/qits-ci-runner.env`. |
| 3 | Registration could not reach the CI, or it answered 5xx. | Nothing — the restart is the retry. |
| 4 | The CI speaks a protocol version this binary does not. | Install the binary the CI's current install script names. |
| 5 | The CI refused the registration (4xx); its answer is quoted in the journal. | Replace the registration token in the UI and paste the new script. |
| 6 | The state directory is unreadable, or `client.json` is not a usable client. | Delete `client.json` and start again with a fresh registration token. |

## Layout

| Path | What |
|---|---|
| `ci-runner-protocol/` | The runner control-socket wire contract: message records and a codec over a plain `Map`, plus `CiRunnerBinary` naming the binary version released beside it. Depends on nothing. Published as `eu.wohlben.qits:qits-ci-runner-protocol`; qits-ci-service depends on it. |
| `ci-runner/` | The binary. A Quarkus command-mode app — no web stack, it dials out and never listens — compiled to a fully static musl native image, `qits-ci-runner`. |
| `docker/` | `Dockerfile` (the native build, exported as a file) and `Dockerfile.musl-builder` (the toolchain; a copy of qits-ci-daemon's). |
| `packaging/qits-ci-runner.service` | The systemd unit. The install script embeds it verbatim. |
| `scripts/test-install-contract.sh` | Runs the install script offline against stubs and asserts its contract. |
| `scripts/fixtures/runner-install.sh` | A rendering of qits-ci-service's install-script template (today a placeholder that follows the contract). |

Inside `ci-runner/`, `Main` is the only CDI bean. It resolves configuration and news up plain classes:
`RunnerMain` (the flow), `Registration` and `Bearer` (identity), `ControlSocket` (the connection),
`Reservations` (slot arithmetic), `Launcher`/`RunnerArgv` (spec → `docker run`), `Reaper`,
`BootSweep` and `BuildPlane` (the runner's own buildkitd). Every docker call goes through `Docker`,
under a deadline.

## The conversation

    Hello{runnerVersion, capabilityVersion, slots, capabilities} → Ack{capabilityVersion, slots}
    Backlog{queued}*                         pushed whenever the queue changes
    Reserve → Take{run} | Nothing            one outstanding at a time
    Launch{run, step, workloadSpec} → Launched{containerId} | LaunchFailed{detail}
    Reap{run, step, containerName} → Reaped
    Cancel{run}                              remove every container of the run now
    Released{run}                            the run is closed; its slot is free
    Heartbeat                                every 10 s

**`Released` is the only thing that frees a slot.** qits-ci drives the run, and a red step skips the
rest, so the runner cannot tell "the last step was reaped" from "the next step is not launched yet";
the host knows and says so.

**Every session starts clean.** When the connection drops, qits-ci fails the runs this runner held,
so on every (re)connect the runner removes all containers carrying its own label
`qits.ci.runner=<id>` — and only those — before it says `Hello`.

**The build plane.** A step whose spec sets `buildPlane` (qits-ci's `docker: true` or `build: true`)
or binds the docker socket gets the runner's own `qits-ci-runner-buildkitd` (privileged, state volume
`qits-buildkitd-state`, on the runner-owned bridge network `qits-ci-runner`), started on first need.
The step joins that network and receives `BUILDKIT_HOST=tcp://qits-ci-runner-buildkitd:1234` unless
its spec already carries the key — an empty value is qits-ci's "switched off" and is never overwritten.
A step that names its own network as well is attached to both, which needs Docker Engine 25 or newer.

## The install-script contract

qits-ci-service renders the install script from `service/src/main/resources/runner-install.sh.tmpl`.
For `scripts/test-install-contract.sh` to run a rendering of it offline, the template must honour:

1. Every absolute path the script writes is prefixed with `${QITS_INSTALL_ROOT:-}`: the binary at
   `$ROOT/usr/local/bin/qits-ci-runner`, the env file at `$ROOT/etc/qits-ci-runner.env`, the unit at
   `$ROOT/etc/systemd/system/qits-ci-runner.service`.
2. It takes `docker`, `id`, `useradd`, `systemctl` and `curl` from `PATH`, never by absolute path.
   The root check is `id -u` answering `0`.
3. The binary lands executable at `$ROOT/usr/local/bin/qits-ci-runner`, downloaded with `curl`; when
   an executable is already there (a rotation) it is kept and not downloaded again.
4. `$ROOT/etc/qits-ci-runner.env` is mode 0600 and carries exactly `QITS_CI_RUNNER_URL`,
   `QITS_CI_RUNNER_ID`, `QITS_CI_RUNNER_REGISTRATION_TOKEN`, `QITS_CI_RUNNER_STATE_DIR` and
   `QITS_CI_RUNNER_SLOTS`, one `KEY=value` per line; a rotation rewrites it with the new token.
5. The unit written is byte-identical to `packaging/qits-ci-runner.service`.
6. `systemctl` is called with `daemon-reload`, `enable --now qits-ci-runner` and
   `restart qits-ci-runner`.
7. The registration token appears in no line the script prints, on stdout or stderr.

The script does not touch the state directory: re-registration on rotation is the binary's.

## Releasing

`.config/qits/release.yml` rides the `daemon` archetype: the binary `qits-ci-runner` and the jar
`eu.wohlben.qits:qits-ci-runner-protocol` leave together, the jar after the binary so a pin never
names bytes that are not in the store yet. The jar's version is the binary's (`CiRunnerBinary`), so
qits-ci pins the runner it hands out by pinning the jar in its pom. Nothing released here reaches a
runner host by itself: a host runs what its install script downloaded.
