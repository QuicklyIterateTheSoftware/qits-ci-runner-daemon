# qits-ci-runner-daemon

The **qits CI runner**: a container you start on any Linux machine with docker so that machine runs
CI steps for the platform. It registers once, holds one outbound connection to qits-ci, reserves
runs when it has a free slot, and starts and removes each step's container with the host's docker.
qits-ci still drives every run — it decides the steps, reads their output and records the verdict;
the runner only starts the containers it is told to start and removes the ones it is told to remove.

    ./mvnw verify                           # a clone of this repo alone builds and tests green
    sh scripts/test-install-contract.sh     # the install script's contract, offline
    docker build -t qits-ci-runner:dev -f docker/Dockerfile .   # the image (after the toolchain; see docker/Dockerfile)

## Installing a runner (for the person at the machine)

A runner is **one docker container** on your machine: the static `qits-ci-runner` binary in the image
`registry.qits.<domain>/qits/qits-ci-runner:<version>`, started with docker's own restart policy
(`unless-stopped`) as its only supervisor and the host's docker socket mounted so it can start CI
steps. There is no service to install, no binary on the host and no systemd unit.

### What you need

- **Linux on x86-64** with **Docker** installed and running (`docker version` answers).
- **Outbound HTTPS** to the platform's domain (the CI service, its identity provider and its
  registry). **No inbound port** is ever opened: the runner only dials out, so a VM behind a home
  router or a NAT works as it is.
- **root**, or a user in the `docker` group — the install line uses `sudo`.

### Install

1. In the CI UI, open **Runners**, create a runner (or pick an existing one) and open its install
   panel.
2. **Copy the one line** the panel shows and **paste it into a shell** on the machine. It looks like

       curl -fsSL -H 'Authorization: Bearer qits_tok_…' https://ci.qits.<domain>/ci/api/runners/install.sh | sudo env QITS_CI_RUNNER_URL='https://ci.qits.<domain>' QITS_CI_RUNNER_ID='<id>' QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_…' QITS_CI_RUNNER_SLOTS='<n>' sh

That is the whole install, and everything in it goes through the platform's **public edge** — the
same `https://…qits.<domain>` names a browser uses. `curl` fetches the generic install script with the
runner's one-time registration token, and `sudo … sh` runs it with this runner's four values in its
environment. The script carries no secret of its own and names no runner; the token is in the line
twice, so treat the line as a secret until the runner has registered. Because the script runs in a
`sh` of its own, a refusal ends that process and never the shell you pasted into. (A user in the
`docker` group can delete the word `sudo`; `env … sh` does the rest.)

The script refuses, one sentence each, when a value is missing or `docker version` does not answer.
Otherwise it:

1. logs in to `registry.qits.<domain>` as `token` with the registration token — in a throwaway
   `docker --config` directory, deleted right after, so your own `~/.docker/config.json` is never
   touched — and pulls the runner image it was rendered with;
2. removes every container already labelled `qits.ci.runner.process=<id>` (an earlier install of this
   runner);
3. keeps the state volume `qits-ci-runner-state-<first 8 of id>` but deletes `client.json` from it, so
   the token in the line is the one that registers;
4. starts the runner:

       docker run -d --name qits-ci-runner-<id8>-<version> --restart unless-stopped \
         --label qits.ci.runner.process=<id> --label qits.ci.runner.version=<version> \
         -v /var/run/docker.sock:/var/run/docker.sock \
         -v qits-ci-runner-state-<id8>:/var/lib/qits-ci-runner \
         -e QITS_CI_RUNNER_URL=… -e QITS_CI_RUNNER_ID=… -e QITS_CI_RUNNER_SLOTS=… \
         -e QITS_CI_RUNNER_REGISTRATION_TOKEN \
         registry.qits.<domain>/qits/qits-ci-runner:<version>

   (no `--network`: docker's default bridge), and prints

       qits-ci-runner started; watch: docker logs -f qits-ci-runner-<id8>-<version>

and never the token: the login reads it on stdin, and the `run` passes it as `-e
QITS_CI_RUNNER_REGISTRATION_TOKEN`, which docker fills from its own environment — it is in no command
line.

### What a good first start looks like

    docker logs -f qits-ci-runner-<id8>-<version>

shows, within a few seconds:

    ci-runner <version> is running in container <id>
    ci-runner registered as <runner id>
    ci-runner connected slots=2

`registered` appears once, on the very first start: the runner exchanged its registration token for
its own credentials and stored them in `client.json` on its state volume (mode 0600). The token is
never used again. From then on the runner mints its access token at the idp's public token endpoint
(`https://idp.qits.<domain>/idp/token`, `client_credentials` with its id and secret in the form body
— the edge would take an HTTP Basic header for its own) and dials `wss://ci.qits.<domain>/ci/runners/socket`;
both addresses come from the registration answer. `connected slots=2` appears on every connection,
with the number of runs the CI lets this runner hold at once — the CI's setting for the runner wins
over the `QITS_CI_RUNNER_SLOTS` the machine advertises. After that you will see `took run …`,
`launched run … step …` and `released run …` as work arrives.

### Updating

Nothing to do: a runner updates itself. The CI pins the runner version it hands out, and a runner
that connects with any other version is told to become it. `docker logs` of the old container then
shows

    ci-runner upgrade to <new> requested; draining <n> held run(s)
    ci-runner pulled registry.qits.<domain>/qits/qits-ci-runner:<new>
    ci-runner released run …                      (for each run it still held)
    ci-runner started its successor qits-ci-runner-<id8>-<new> on …; waiting up to 180s for it to take over
    ci-runner retired by the host (…); qits-ci-runner-<id8>-<new> has taken over
    ci-runner exits: retired

In order:

1. **Drain.** The old runner takes no new run from the moment it is told; the runs it holds finish.
2. **Pull.** It pulls the new image under its own access token as the registry login (a throwaway
   `docker --config` again) and checks the image digest when the CI sent one.
3. **Start the successor** once it holds no run: a new container, `qits-ci-runner-<id8>-<new>`, with
   the old one's parameters read from `docker inspect` of itself — its environment (minus the spent
   registration token), mounts, restart policy and network — and the new image and version label.
   The successor uses the same state volume, so it is already registered.
4. **Hand over.** The successor connects; the CI gives it the slots and tells the old one to retire.
   The old one takes its own restart policy away (`docker update --restart=no`) and exits 0.
5. **Clean up.** After its first connection the new runner removes its predecessors — every container
   of this runner with another version label — waiting up to a minute for one still running to exit.
   `docker ps -a` then shows one runner container.

**Rollback is automatic.** If the successor has not taken over within
`QITS_CI_RUNNER_ROLLOVER_TIMEOUT` (180 s) — it crashes, cannot pull, cannot connect — the old runner
removes it (`docker rm -f`) and carries on, still draining, and tries again later (backing off to
once every ten minutes). A failed pull or start is retried the same way. While it drains the old
runner holds no slots, so a runner that cannot update takes no work until it can; its log says why.

**Runners older than self-update** (installed with the systemd unit and a bare binary) cannot update
themselves: they drop the CI's update frame and keep running the old version — connected, but
given no slots, so they take no work. Re-paste the install
line once — replace the registration token in the CI UI to get a fresh one — and remove the old unit
first:

    sudo systemctl disable --now qits-ci-runner
    sudo rm /etc/systemd/system/qits-ci-runner.service /usr/local/bin/qits-ci-runner /etc/qits-ci-runner.env
    sudo systemctl daemon-reload

(`/var/lib/qits-ci-runner` on the host and the `qits-ci-runner` system user are no longer used and
can go too; the old runner's `qits-ci-runner-buildkitd` builder is adopted by the new one as it is.) From then on the runner is a
container and updates itself.

### Rotating the registration, or replacing a runner

In the CI UI, **Replace registration token** on the runner, then paste the **new line** into a shell
exactly as the first time. The script removes the running container, keeps the state volume, deletes
the stored client and starts the runner again with the new token, which registers and replaces the
client. The old line stops working: its token is deleted when the new one is minted. The same paste is
how you reinstall a runner whose container you removed by hand.

### Removing a runner

    docker rm -f $(docker ps -aq --filter label=qits.ci.runner.process=<id>)

then **delete the runner in the CI UI**, which decommissions its credentials so the stored client can
never connect again. To clean the machine as well:

    docker volume rm qits-ci-runner-state-<id8>
    docker rm -f qits-ci-runner-buildkitd            # if the runner ever built images
    docker rm -f $(docker ps -aq --filter label=qits.ci.runner=<id>)   # step containers, if any are left

### Environment

The install script passes the first four to the container; the rest are for an operator with a
reason, and the install script passes each one that is set in its environment (put it in the line's
`env …`). A successor inherits whatever its predecessor was given.

| Variable | Meaning | Default |
|---|---|---|
| `QITS_CI_RUNNER_URL` | The CI service's base url — its public edge name, e.g. `https://ci.qits.example.eu`. | required |
| `QITS_CI_RUNNER_ID` | The runner id the CI minted for this runner. | required |
| `QITS_CI_RUNNER_REGISTRATION_TOKEN` | One-time registration token. Needed only until registered; a *different* token later means "register again". A successor is never given it. | required until registered |
| `QITS_CI_RUNNER_SLOTS` | How many runs this machine is set up for. Advertised; the CI's number is the cap. | `1` |
| `QITS_CI_RUNNER_STATE_DIR` | Where `client.json` lives — the state volume's mount point. | `/var/lib/qits-ci-runner` |
| `QITS_CI_RUNNER_DOCKER_BINARY` | The docker CLI to run. | `docker` (the image's) |
| `QITS_CI_RUNNER_DOCKER_TIMEOUT` | Seconds any one docker call may take before it is killed. | `120` |
| `QITS_CI_RUNNER_BUILDKIT_IMAGE` | The image of the runner's own buildkitd. | `moby/buildkit:v0.33.0` |
| `QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES` | Comma list of `host[:port]` the builder speaks plain HTTP to. | empty |
| `QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS` | Comma list of `from=to` registry rewrites; `to` may carry a path (`mirror:8080/hub`). | empty |
| `QITS_CI_RUNNER_ROLLOVER_TIMEOUT` | Seconds a successor has to take over before it is removed and the update retried. | `180` |
| `QITS_CI_RUNNER_TELEMETRY_URL` | The OTLP endpoint the runner's own log is shipped to (`/v1/logs` is appended). Empty switches it off. Not passed by the install script yet. | derived: `https://ci.<domain>` → `https://observability.<domain>/observability/api/otel`; none when the CI url's host is not `ci.…` |
| `QITS_CI_RUNNER_PRINT_VERSION` | `1`: print the runner version and exit 0, needing nothing else. For the image's smoke test. | unset |

Both builder lists stay empty on a machine that reaches the platform through its public domain —
every registry there is HTTPS. A runner on the platform host's own network (`qits-net`) needs the
values qits-containers gives the platform's builder (`qits.containers.buildkit.http-registries` and
`qits.containers.buildkit.registry-mirrors`), or its builds cannot pull the committed `FROM` lines or
push to the platform's plain-HTTP registry. For example:

    QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES=dev-qits-artifacts:8080,dev-qits-platform-mirror:8080
    QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS=registry.dev.localhost:8080=dev-qits-artifacts:8080,mirror.dev.localhost:8080=dev-qits-platform-mirror:8080,docker.io=dev-qits-platform-mirror:8080/hub

Changing either replaces the builder on its next use (its cache volume survives).

### The runner's own log, on the platform

`docker logs` on the machine is always the whole log. Every line at INFO and above is **also**
shipped to qits-observability, so a person on the platform can read a runner on somebody else's
machine: OTLP `http/protobuf` to `https://observability.<domain>/observability/api/otel/v1/logs`,
through the same public edge, with the runner's own access token as the bearer. The lines land in
the `_service/qits-ci-runner` source, one bucket for every runner, told apart by the resource
attributes `qits.ci.runner.id` (the id in the CI UI), `service.instance.id` (the runner's container),
`service.version` and `host.arch`:

    GET https://observability.<domain>/observability/api/telemetry/logs?source=_service/qits-ci-runner

Shipping never slows the runner: lines wait in a bounded queue (the oldest go first when it is full,
and how many went is shipped as a line of its own), leave in batches every five seconds, and a batch
the collector refuses is dropped rather than retried — one log line says shipping stopped, one that it
resumed. Lines logged before the runner has credentials (its registration) are sent once it does.

### Exit codes

A healthy runner never exits; docker restarts it after any of these. The one clean exit, 0, is a
runner retired by the CI (after an update, or a decommission), which takes its own restart policy away
first.

| Code | Meaning | What to do |
|---|---|---|
| 0 | Retired by the CI. | Nothing — its successor, if any, is running. |
| 2 | A variable is missing or unparseable (the log line names it), or the runner is not registered and has no token. | Re-paste the install line. |
| 3 | Registration could not reach the CI, or it answered 5xx. | Nothing — the restart is the retry. |
| 4 | The CI speaks a protocol version this runner does not. | Re-paste the install line the CI currently hands out. |
| 5 | The CI refused the registration (4xx); its answer is quoted in the log. | Replace the registration token in the UI and paste the new line. |
| 6 | The state directory is unreadable, or `client.json` is not a usable client. | Replace the registration token in the UI and paste the new line (it resets `client.json`). |

## Layout

| Path | What |
|---|---|
| `ci-runner-protocol/` | The runner control-socket wire contract: message records and a codec over a plain `Map`, plus `CiRunnerBinary` naming the runner version (the image tag) released beside it. Depends on nothing. Published as `eu.wohlben.qits:qits-ci-runner-protocol`; qits-ci-service depends on it. |
| `ci-runner/` | The binary. A Quarkus command-mode app — no web stack, it dials out and never listens — compiled to a fully static musl native image, `qits-ci-runner`. |
| `docker/` | `Dockerfile` (the native build; its default target `image` is the runner image, `binary` exports the bare file) and `Dockerfile.musl-builder` (the toolchain; a copy of qits-ci-daemon's). |
| `scripts/test-install-contract.sh` | Runs the install script offline against a stub docker and asserts its contract. |
| `scripts/fixtures/runner-install.sh` | The reference implementation of the install contract. qits-ci-service's template (`service/src/main/resources/runner-install.sh.tmpl`) is written to match it exactly. |

Inside `ci-runner/`, `Main` is the only CDI bean. It resolves configuration and news up plain classes:
`RunnerMain` (the flow), `Registration` and `Bearer` (identity), `ControlSocket` (the connection),
`Reservations` (slot arithmetic), `Launcher`/`RunnerArgv` (spec → `docker run`), `Reaper`,
`BootSweep` and `LogTail` (removal, and a removed container's last output), `Telemetry` and
`OtlpLogs` (its own log, shipped), `BuildPlane` (the runner's own buildkitd), and `Rollover` with `SelfContainer` and `SelfSpec` (self-update: which
container this is, what it was started with, and its successor). Every docker call goes through
`Docker`, under a deadline.

## The conversation

    Hello{runnerVersion, capabilityVersion, slots, capabilities} → Ack{capabilityVersion, slots}
    Backlog{queued}*                         pushed whenever the queue changes
    Reserve → Take{run} | Nothing            one outstanding at a time
    Launch{run, step, workloadSpec} → Launched{containerId} | LaunchFailed{detail}
    Reap{run, step, containerName} → Reaped{logTail?}   the container's last output, read before removal
    Cancel{run}                              remove every container of the run now
    Released{run}                            the run is closed; its slot is free
    Upgrade{version, image, sha256?}         become this version: drain, pull, start a successor
    Retire{reason}                           a runner of the pinned version took over; exit 0
    Heartbeat                                every 10 s

**`Upgrade` and `Retire` are frozen**, with `Hello.runnerVersion`: they are how a runner of any older
version is told to update, so their wire shape never changes (see `Upgrade`'s javadoc). A runner too
old to know them drops them as frames of an unknown type and stays connected — the host keeps it
draining, and a person re-pastes the install line (see "Updating").

**A runner's own container is labelled `qits.ci.runner.process=<id>`**, and its version
`qits.ci.runner.version=<version>` — never `qits.ci.runner=<id>`, the step label the sweep below
removes, which a spec can never set either.

**`Released` is the only thing that frees a slot.** qits-ci drives the run, and a red step skips the
rest, so the runner cannot tell "the last step was reaped" from "the next step is not launched yet";
the host knows and says so.

**A step's last output is read before its container goes.** Every removal — a `Reap`, a `Cancel`,
the sweep below — first runs `docker inspect` (how it ended) and `docker logs --tail 200` (both
streams, merged), bounded to 32 KiB keeping the newest lines, with anything bearer- or
password-shaped redacted. A reap sends it to qits-ci as `Reaped.logTail`, led by `[container exited
<code>]` when the container had exited — the only record of a step whose daemon never dialled back.
A cancel or a sweep has no answer to carry it in and writes it to the runner's own log. A docker
that cannot produce the logs is a null tail, never a failed removal.

**Every session starts clean.** When the connection drops, qits-ci fails the runs this runner held,
so on every (re)connect the runner removes all containers carrying its own label
`qits.ci.runner=<id>` — and only those — before it says `Hello`.

**The build plane.** A step whose spec sets `buildPlane` (qits-ci's `docker: true` or `build: true`)
or binds the docker socket gets the runner's own `qits-ci-runner-buildkitd` (privileged, state volume
`qits-buildkitd-state`, on the runner-owned bridge network `qits-ci-runner`), started on first need.
The step joins that network and receives `BUILDKIT_HOST=tcp://qits-ci-runner-buildkitd:1234` unless
its spec already carries the key — an empty value is qits-ci's "switched off" and is never overwritten.
A step that names its own network as well is attached to both, which needs Docker Engine 25 or newer,
and the builder joins that network too, so its pulls and pushes resolve that network's names.

The builder's `buildkitd.toml` is rendered the way qits-containers' `PlatformBuildkit` renders the
platform builder's: `networkMode = "host"` and docker's embedded DNS (`127.0.0.11`) for its `RUN`s,
then one `[registry."host"]` table per host from `QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS` and
`QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES`. With both unset — the EDGE plane's default — the toml
carries no `[registry.*]` table at all: nothing is rewritten, and every registry is reached exactly
as the step spelled it. It reaches the container as an environment value the
container writes to `/etc/buildkit/buildkitd.toml` itself. The container carries a stamp label, a
hash of the image, the toml, the start script and the CA bundle mounted into it (below), and a
builder whose stamp differs from the configured one is removed and started again with the new
configuration.

**The step image is pulled under the launch's own login.** When a spec's environment carries
`QITS_CI_REGISTRY_AUTH_CONFIG` — the docker `config.json` document qits-ci sends as the run's
registry credential — the runner runs that launch's `docker image inspect` and `docker pull` as
`docker --config <dir>`, where `<dir>` is a fresh 0700 directory holding the document as a 0600
`config.json`, deleted as soon as the pull is over. The host's own docker config is never written,
the document is never logged, and a failed pull's detail has it redacted. Without the key the pull
runs under the host's config, as before. This is what lets an EDGE step's image come from the
registry's public vhost, which answers an anonymous `/v2` with 401.

## Build steps

A step's spec sends it down one of two paths, decided by qits-ci and never by the runner:

- **`build: true` or `docker: true`** — the step gets the runner's own buildkitd: it joins the
  `qits-ci-runner` bridge and is handed `BUILDKIT_HOST=tcp://qits-ci-runner-buildkitd:1234` (unless
  its spec already set the key), and its `buildctl`/`docker buildx` calls run there. The container
  itself never touches the host's docker socket.
  - `docker: true` *also* binds the host's own `/var/run/docker.sock` into the step — the one host
    path this runner ever mounts into a step, and only because the spec declared it (see
    `AGENTS.md`'s "Untrusted input"). A step that binds the socket gets the build plane too, the same
    as `build: true`, since a socket is a builder whoever holds it.
- **Neither key set** — a plain step: no builder is touched, no `BUILDKIT_HOST` is set, and the
  container joins no network beyond whatever its own spec names.

**What the host needs for a build to succeed.** Pulling and pushing against the platform's registry
needs a certificate chain the builder can validate. On the **EDGE plane** — a runner reached through
the public install line, dialling `registry.qits.<domain>` and friends — that chain is a normal
publicly-issued one (Let's Encrypt), and the runner's own `moby/buildkit` image already carries a CA
bundle able to validate it: the pinned image's `Dockerfile` builds its default (Alpine) export stage
from `alpine:3.23`, whose base layer ships `/etc/ssl/certs/ca-certificates.crt` — Alpine's
`ca-certificates-bundle` — without needing an explicit `apk add`; only the image's Ubuntu-export
variant, which this runner does not use, installs the package by hand. On top of that, the builder
would mount the *host's* CA bundle read-only over the same path when it can find one — probing
`/etc/ssl/certs/ca-certificates.crt`, then `/etc/pki/tls/certs/ca-bundle.crt`, then
`/etc/ssl/cert.pem` — **but only a runner that is not itself in a container probes**. The runner
container cannot see the host's filesystem: the probe would find the runner image's own bundle,
and the bind it produced would be resolved by the host's docker on the host, where a RHEL-style host
has no file at the Debian path and docker would mount an empty directory over the builder's
certificates. So a containerised runner — every installed one — mounts nothing, logs that once, and
the builder trusts the image's bundle, which covers the platform's publicly-issued chain. **An
operator's own root, or a self-signed edge, is out of scope**: nothing here adds a certificate to the
builder's trust store.

On the **INTERNAL plane** — a runner sharing the platform host's `qits-net` — both
`QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES` and `QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS` are set (see
"Environment" above), and the builder speaks plain HTTP to the platform's own registry and mirror
aliases instead, so no certificate is in question there at all. **On the EDGE plane both stay empty**
— every registry a builder reaches from there is HTTPS with a public chain, and setting either would
rewrite an in-network spelling nothing on that plane resolves.

## The install-script contract

qits-ci-service renders the generic install script from
`service/src/main/resources/runner-install.sh.tmpl` — filling in only the pinned image (its
registry's public host, `CiRunnerBinary.IMAGE_REPOSITORY`, `CiRunnerBinary.VERSION`) and the version
— and serves it at `GET /ci/api/runners/install.sh`, readable with a registration token.
`scripts/fixtures/runner-install.sh` is the **reference implementation**: the template is written to
match it exactly, and `scripts/test-install-contract.sh` runs it offline the way the install line
does — on stdin, with the four values in its environment and a stub `docker` on `PATH` recording its
argv. The contract (the test's header spells each assertion):

0. It carries no token and names no runner: `QITS_CI_RUNNER_URL`, `QITS_CI_RUNNER_ID`,
   `QITS_CI_RUNNER_REGISTRATION_TOKEN` and `QITS_CI_RUNNER_SLOTS` come from its environment, and one
   missing is a refusal naming it, before docker is asked anything.
1. `docker version` must answer, or it refuses and runs nothing else.
2. `docker --config <tmp> login <registry host> -u token --password-stdin` with the registration token
   on stdin, `docker --config <tmp> pull <image>`, and the directory deleted. A failed login or pull
   is a refusal that leaves an installed runner alone.
3. `docker rm -f` every container `docker ps -aq --filter label=qits.ci.runner.process=<id>` lists.
4. The state volume `qits-ci-runner-state-<id8>` is kept; `client.json` is removed from it.
5. `docker run -d` exactly as in "Install" above, plus `-e NAME=value` for each tuning variable that
   is set. A successor (`RunnerArgv.runSuccessor`) produces the same shape from `docker inspect`.
6. It prints `qits-ci-runner started; watch: docker logs -f <name>`.
7. The registration token appears in no line it prints and in no docker argv.

## Releasing

`.config/qits/release.yml` publishes two artifacts: the image `qits/qits-ci-runner`
(`<registry>/qits/qits-ci-runner:<version>`, built from `docker/Dockerfile`'s `image` target) and
the jar `eu.wohlben.qits:qits-ci-runner-protocol`, the jar after the image so a pin never names an
image that is not in the registry yet. It declares both slots itself and names `java-service` for the
family, as qits-workspace-daemon does for the same image-plus-jar shape. The jar's version is the
image's tag (`CiRunnerBinary`), so qits-ci pins the runner it hands out — in the install script and
in every `Upgrade` — by pinning the jar in its pom. The release request builds the image, loads it
into the step's docker, and asserts it prints the tree's version (`QITS_CI_RUNNER_PRINT_VERSION=1`)
and refuses to start with no environment (exit 2). No bare binary is published.
