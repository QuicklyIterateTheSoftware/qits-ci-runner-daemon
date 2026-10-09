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

Nothing to do: a runner updates itself (all but a deployer-managed one, which its deployer replaces — see "On the platform host"). The CI pins the runner version it hands out, and a runner
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
2. **Pull.** It pulls the new image under its own commissioned client pair (`client.json`'s
   `clientId`/`secret`, not its short-lived access token — the edge's docker realm accepts a client
   secret or an opaque `qits_tok_…` as the Basic password, never a JWT) as the registry login (a
   throwaway `docker --config` again) and checks the image digest when the CI sent one.
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

### Quarantine and health checks

The CI stops giving a runner work — its slots forced to 0, the same as while it drains for an
update — after it has caused enough failures in a row: an image pull or container start the runner
itself could not do, a step daemon that never dials back, or a control-socket connection lost too
often to be believed a fluke. It also happens when a periodic **health check**, a pseudo-build the CI
runs on the runner like any other — it clones a repository and runs `echo hello world` in the
standard CI image — comes back failed. A **new runner starts quarantined**, and stays that way until
its first health check passes; while quarantined, the check repeats hourly. `docker logs` shows why:

    ci-runner is quarantined since <since>: <reason>; it takes no new runs until reinstated
    ci-runner reinstated by <by>

The runner does nothing differently while quarantined — it still answers `Hello` and still sends
`Reserve` when the CI says there is a backlog, the same as any other session; the CI is the one that
answers every `Reserve` with `Nothing` until the quarantine lifts, exactly as it does for a draining
runner. The log line, and the runner's own idea of its status, are for the person at the machine.

An admin lifts a quarantine from the Runners page in the CI UI — greenlighting it directly, or
triggering an immediate health check rather than waiting for the hourly one.

### The node health check

Beside the pseudo-build, the CI can ask the runner itself what its node looks like: a `healthCheck`
frame, answered by `healthChecked` with every named check's outcome — the facts an operator would
otherwise read with `docker` on the node. It is a diagnosis and never gates: quarantine follows the
pseudo-build alone. The checks run off the socket's thread, in this order, each under its own
deadline (three docker deadlines unless it names one), so a check that hangs or throws fails by
itself and the rest are still reported:

| Check | Data | Ok when |
|---|---|---|
| `docker` | `{serverVersion}` | `docker version` answers a server version |
| `nodeInventory` | `{containers, volumes, runnerContainer}` | every listing and inspect of the runner's own labelled objects (`qits.ci.runner=<id>`, and its own container by `qits.ci.runner.process=<id>`) was answered |
| `session` | `{connected, connectedSince, slots, held}` | the runner is connected (admitted by an `Ack`) |
| `buildkit` | `{container, presence, state, stamp, configuredStamp, address, image, stateVolume}` | `qits-ci-runner-buildkitd` is running under the stamp the next build would start it with — or was never created, because no build on this node has needed it yet |
| `network` | `{network, presence, driver, builder}` | the `qits-ci-runner` bridge exists — or is missing while no builder exists either ("not needed yet") |
| `idRange` | `{idRange, fullIdRange, narrow}` | the user namespace maps the full uid/gid space; fails with the same words as the boot-time warning (a rootless docker or unprivileged LXC host maps too few; qits-ci then hands it no build step), unknown is ok |
| `stepImage` | `{image, requested, present, pulled}` | the step image is on the node, or `docker pull` fetches it within 180 s |

The first three are qits-runner-javalib's defaults (`qits-runner-toolkit`, `HealthChecks`), the rest
CI's own. Every check is read-only but one: `stepImage` pulls an image that is not here — what the
next run would do anyway — and records it for housekeeping like a launch's. It pulls under this
host's docker login, never a run's (a health frame carries no credential), so on a registry that
refuses an anonymous pull only an image already present passes. The image is the request's `image`
when it names one (the host sends it resolved, with its registry, as it resolves a step's), else
`qits/build-images/ci-base:latest` on `registry.<domain>` when the CI url is `https://ci.<domain>`,
else that bare name, which only a local copy answers.

`nodeInventory` lists the step containers (each with the run id in its `qits.ci.runner.run` label;
the inventory's numeric `rowId` reads null for these UUIDs) and the runner's own container. Its
`volumes` are the volumes labelled `qits.ci.runner=<id>`, and this runner labels none: the state
volume and the builder's volume are found by name, the builder by `buildkit` above.

**Adding a check** is a class in `ci-runner`: a `@Singleton` implementing the toolkit's
`RunnerHealthCheck`, with no constructor argument, reading what it needs from the context
(`ctx.docker()`, `ctx.session()`, `ctx.lookup(BuildPlane.class)`, `Capabilities`, `StepImages`,
`CiHealth.StepImageTarget`). `Main` collects every such bean with `@Inject @All
List<RunnerHealthCheck>` — resolved by ArC at build time, so the native image needs no reflection —
and `CiHealth.ordered` fixes the wire order: the four above in that order, any other after them by
name.

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
| `QITS_CI_RUNNER_BUILDKIT_STATE_VOLUME` | The volume the runner's own buildkitd keeps its content store in. Changing it is stamp material and replaces the builder (the old volume is left as is). | `qits-buildkitd-state` |
| `QITS_CI_RUNNER_ROLLOVER_TIMEOUT` | Seconds a successor has to take over before it is removed and the update retried. | `180` |
| `QITS_CI_RUNNER_TELEMETRY_URL` | The OTLP endpoint the runner's own log is shipped to (`/v1/logs` is appended). Empty switches it off. Not passed by the install script yet. | derived: `https://ci.<domain>` → `https://observability.<domain>/observability/api/otel`; none when the CI url's host is not `ci.…` |
| `QITS_CI_RUNNER_PRINT_VERSION` | `1`: print the runner version and exit 0, needing nothing else. For the image's smoke test. | unset |
| `QITS_CI_RUNNER_SELF_UPDATE` | `false` (or `0`): the runner never updates itself — it ignores the CI's update frame, logs that it is deployer-managed, and advertises the capability label `qits.ci.runner.self-update=false`. Only for a deployer-managed runner, one whose deployer replaces it (see "On the platform host"). | `true` |

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

### Disk

The runner cleans up after itself; there is nothing to configure, and it never runs `docker system
prune` or `docker image prune -a` — the machine may hold things that are not the runner's.

- **Runner images.** After an update the new runner removes the old version's container and then its
  image (`<registry>/qits/qits-ci-runner:<old version>`). Every housekeeping pass also removes any
  other `qits/qits-ci-runner` image no container uses — leftovers from before this existed. The
  image the runner is running is never removed.
- **Step images.** Every image a step was launched from is recorded, with when it was last used, in
  `images.json` in the state volume. Every 6 hours (and once after start), a recorded image unused
  for 7 days that no container was created from is removed, then `docker image prune -f` removes
  dangling layers. Images the runner never launched are not touched. A pass never removes an image
  while a launch is in flight; that image waits for the next pass.
- **Build cache.** buildkitd's own garbage collector (`qits-buildkitd-state`): cache unused for 72h
  goes, and the total is kept under 20GB, least recently used first. It runs when the builder starts
  and after each build. A builder an older runner started without this policy is replaced once, when
  no run is held; its cache volume is kept.

If a removal fails (docker refuses an image still in use, say), the runner logs it and carries on.

### Exit codes

A healthy runner never exits; docker restarts it after any of these. The one clean exit, 0, is a
runner retired by the CI (after an update, or a decommission), which takes its own restart policy away
first.

| Code | Meaning | What to do |
|---|---|---|
| 0 | Retired by the CI, or decommissioned because the runner was deleted. | Nothing — its successor, if any, is running; a deleted runner's container and volume are removed by its helper (see "Deleting a runner"). |
| 2 | A variable is missing or unparseable (the log line names it), or the runner is not registered and has no token. | Re-paste the install line. |
| 3 | Registration could not reach the CI, or it answered 5xx. | Nothing — the restart is the retry. |
| 4 | The CI speaks a protocol version this runner does not. | Re-paste the install line the CI currently hands out. |
| 5 | The CI refused the registration (4xx); its answer is quoted in the log. | Replace the registration token in the UI and paste the new line. |
| 6 | The state directory is unreadable, or `client.json` is not a usable client. | Replace the registration token in the UI and paste the new line (it resets `client.json`). |

### Exit codes of `qits-ci-runner health`

`qits-ci-runner health` is not a runner: it is the container healthcheck, answered before anything
starts, reading one file's mtime and printing one line about it. It is selected only by that explicit
first argument — the image started bare is the runner, as always.

| Code | Meaning |
|---|---|
| 0 | `<state dir>/heartbeat` was touched within the last 60 s: the runner is connected to the CI. |
| 1 | The heartbeat file is missing (never admitted) or older than 60 s (disconnected, redialling or wedged). |
| 2 | The state directory itself does not exist. |

The state directory is `QITS_CI_RUNNER_STATE_DIR`, default `/var/lib/qits-ci-runner`. The runner
touches the file when the CI acknowledges its `Hello` and on every heartbeat it sends (every 10 s),
and never while disconnected; failing to touch it is logged at debug and never stops the runner.

## On the platform host

Not deployed on the live estate: the platform's own CI runs on an external runner, moved there for
resource reasons, and the platform host itself runs no runner today. A same-node runner — the CI
known as `localhost`, run as an ordinary qits-deployments deployable instead of installed with the
install line — stays only a future default for a *fresh* bootstrap, and is follow-up work.

The pieces for that are already here, unused for now:

- **`qits-ci-runner health`**, the subcommand described above, reading the heartbeat file — the
  probe a deployer's health gate would run, since nothing listens in this container for an HTTP
  check to reach.
- **`QITS_CI_RUNNER_SELF_UPDATE=false`** (capability label `qits.ci.runner.self-update=false`): the
  runner ignores the CI's `Upgrade` and logs that it is deployer-managed instead of starting a
  successor beside a swarm's, for the case where something other than the runner itself replaces
  the container.

What is deliberately *not* in this repository yet is `.config/qits/deployments.yml` — the file that
would make qits-deployments treat this image as a platform deployable. Carrying it makes every
release deploy `qits-ci-runner` onto the platform host, which the live estate does not want; it is
held back until the fresh-bootstrap follow-up adds it back, alongside the docker-socket grant, the
state volume, and the stop-first update order it would need.

### Does a container on the platform host reach the public names at all?

A same-node runner's step containers would dial the same public edge names as any other runner —
`ci.<domain>`, `registry.<domain>`, `mirror.<domain>`, `githost.<domain>`, `idp.<domain>` — rather
than `qits-net` aliases, so a step keeps working whichever host it happens to land on. That only
works if the platform host can **hairpin**: route a packet a container on that host sent to the
host's own public address back to itself through the NAT gateway, rather than the gateway dropping it
for not coming from outside. Not every NAT does this, so it is worth measuring before building
anything for it rather than after.

The test, worth repeating if this is ever revisited:

    docker run --rm curlimages/curl -sI https://ci.<domain>/ci/q/health/ready

`HTTP/2 401` means the name resolves and the edge answered — hairpin works. A refused connection or a
timeout means it does not, and step containers would then need `--add-host` entries (a
`QITS_CI_RUNNER_EXTRA_HOSTS`-shaped variable, alongside the two builder lists above) pointing each
vhost at wherever the edge's published 443 actually answers from that host.

**Measured 2026-09-30 16:38Z, on the live estate**, from a workspace container running on the
platform host (an ordinary workspace has no docker socket, so the probe was `curl` inside that
container rather than `docker run` on the default bridge; both leave the host through the same NAT):
`getent hosts ci.qits.wohlben.eu` → `46.224.171.33`, the public address; `curl
https://ci.qits.wohlben.eu/ci/q/health/ready` → `401` over HTTP/2 in 84 ms from that same address,
and `200` with a bearer. Hairpin works, so `QITS_CI_RUNNER_EXTRA_HOSTS` was **not** built — there was
nothing on this estate for it to fix.

This matters only for the fresh-bootstrap follow-up above, not for today: the live estate runs no
runner on the platform host (see above), so nothing here is exercised until that lands.

## Layout

| Path | What |
|---|---|
| `ci-runner-protocol/` | The runner control-socket wire contract: message records and a codec over a plain `Map`, plus `CiRunnerBinary` naming the runner version (the image tag) released beside it. Depends on nothing but `qits-runner-protocol` (itself dependency-free), for the shared health report. Published as `eu.wohlben.qits:qits-ci-runner-protocol`; qits-ci-service depends on it. |
| `ci-runner/` | The binary. A Quarkus command-mode app — no web stack, it dials out and never listens — compiled to a fully static musl native image, `qits-ci-runner`. |
| `docker/` | `Dockerfile` (the native build; its default target `image` is the runner image, `binary` exports the bare file) and `Dockerfile.musl-builder` (the toolchain; a copy of qits-ci-daemon's). |
| `scripts/test-install-contract.sh` | Runs the install script offline against a stub docker and asserts its contract. |
| `scripts/fixtures/runner-install.sh` | The reference implementation of the install contract. qits-ci-service's template (`service/src/main/resources/runner-install.sh.tmpl`) is written to match it exactly. |

Inside `ci-runner/`, `Main` is the only CDI bean besides the node health checks (`BuildkitCheck`,
`NetworkCheck`, `IdRangeCheck`, `StepImageCheck`, collected into `CiHealth`). It resolves configuration and news up plain classes:
`RunnerMain` (the flow), `Registration` and `Bearer` (identity), `ControlSocket` (the connection),
`Reservations` (slot arithmetic), `Launcher`/`RunnerArgv` (spec → `docker run`), `Reaper`,
`BootSweep` and `LogTail` (removal, and a removed container's last output), `Telemetry` and
`OtlpLogs` (its own log, shipped), `BuildPlane` (the runner's own buildkitd), and `Rollover` with `SelfContainer` and `SelfSpec` (self-update: which
container this is, what it was started with, and its successor). `HealthCommand` is the
`qits-ci-runner health` subcommand and the heartbeat file it reads. Every docker call goes through
`Docker`, under a deadline.

## The conversation

    Hello{runnerVersion, capabilityVersion, slots, capabilities} → Ack{capabilityVersion, slots, registryMirrors?}
    Backlog{queued}*                         pushed whenever the queue changes
    Reserve → Take{run} | Nothing            one outstanding at a time
    Launch{run, step, workloadSpec} → Launched{containerId} | LaunchFailed{detail}
    Reap{run, step, containerName} → Reaped{logTail?}   the container's last output, read before removal
    Cancel{run}                              remove every container of the run now
    Released{run}                            the run is closed; its slot is free
    Upgrade{version, image, sha256?}         become this version: drain, pull, start a successor
    Retire{reason}                           a runner of the pinned version took over; exit 0
    Retire{reason, kind: DELETED}            the runner was deleted: decommission (see "Deleting a runner")
    Quarantined{reason, since}               the CI stops giving this runner work (see "Quarantine and health checks")
    Reinstated{by}                           the quarantine is lifted
    HealthCheck{requestId?, image?} → HealthChecked{ok, detail, requestId?, checks?[{name, ok, detail, data}]}
                                             the node's named checks, on demand (see "The node health check")
    Heartbeat                                every 10 s

**`Upgrade` and `Retire` are frozen**, with `Hello.runnerVersion`: they are how a runner of any older
version is told to update, so their wire shape never changes (see `Upgrade`'s javadoc). A runner too
old to know them drops them as frames of an unknown type and stays connected — the host keeps it
draining, and a person re-pastes the install line (see "Updating").

**The health frames are additive**, and their shape is qits-runner-javalib's `HealthWire`, shared
with every runner kind: `type` first, `requestId` and `checks` left off when absent. `image` is CI's
own, written last and only when set. A runner older than them drops a `healthCheck` as an unknown
type and never answers, so the host times its request out.

**`Retire.kind` is an added field.** It is only on the wire as `DELETED`; absent, or a value this
binary does not know, reads as a self-update's retirement, which never removes a state volume. A
runner too old to know it treats a deleted retirement as an operator's: it takes its restart policy
away and exits 0, so its container stops for good but stays, exited, for a person to remove.

### Deleting a runner

Deleting a runner in the CI UI decommissions its container. A connected runner is sent `Retire{kind:
DELETED}` before its client is revoked; it reserves nothing more, removes the containers of the runs
it held and anything else under its label, deletes `client.json`, takes its own restart policy away,
starts a helper container (`qits-ci-runner-<id8>-decommission`, its own image with the docker socket)
and exits 0. The helper waits for every container labelled `qits.ci.runner.process=<id>` to exit,
removes them, removes the state volume `qits-ci-runner-state-<id8>`, and removes itself; a helper that
could not finish stays, exited, with its log. A runner owns its node's builder, so the helper also
takes the shared `qits-ci-runner-buildkitd` (see "Build steps" below) with it — its container, its
state volume and the runner network — best-effort, on the way to removing the state volume.

A runner that was offline when it was deleted finds out when it dials, and decommissions itself the
same way. Two answers say it, and nothing else does: the CI closing the socket `1008
RUNNER_DELETED` (the bearer is valid and no runner is registered with it), believed at once; or the
token endpoint answering `invalid_client` on every dial for five minutes, believed only as that
unbroken streak. A 5xx, an edge's 401, a refused connection, a timeout or any other close is the
platform being down or restarting, and is redialled forever as before.

**`Quarantined` and `Reinstated` are additive, not frozen.** A runner too old to know them drops
them the same way — it still gets no work while quarantined, because the CI is the one answering
every `Reserve` with `Nothing`, but its log says nothing about why. `CAPABILITY_VERSION` does not
move for them.

**`Ack.registryMirrors` is additive too**, and not only-on-the-first-`Ack` either: qits-ci re-sends
`Ack` whenever the runner's admin-edited slot cap changes, and a runner older than this field simply
ignores the key — see "The build plane" for what it does when it is understood.

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

**Every session starts without leftovers, and a reconnect keeps what it still holds.** On every
(re)connect the runner removes the containers carrying its own label `qits.ci.runner=<id>` — and
only those — before it says `Hello`, except the containers of runs it held when the connection
dropped. Those runs are *carried*: the `Hello` claims them (`heldRuns`), qits-ci keeps the ones it is
still driving (it waits a short grace for the runner to come back before failing them) and names them
in its `Ack` (`adoptedRuns`), and the runner cancels every carried run the host did not keep — which
is all of them against a qits-ci older than the field. So a dropped socket no longer costs a running
step, and a process restart (which carries nothing) still starts from an empty label.

**Reap, then reserve.** The carried runs the host did not keep are cancelled *before* the session
reserves anything: the `Ack`'s slots are not counted until their containers are gone. A qits-ci that
restarted adopts nothing — what it held was in memory — and its boot puts the runs it was driving
back in the queue, so the first `Take` of the new session is routinely the very run whose containers
are being removed. A cancel finds a run's containers by the run label, which the new attempt's
container carries too, so one still running after that `Take` would remove the new attempt's step.

**A `Launch` owns its container name.** A step container's name is qits-ci's and deterministic per
(run, commit, step index), and the host launches a step once per attempt. So whatever already holds
the name when a `Launch` for it arrives is a leftover of an earlier attempt the host no longer
tracks, and the runner removes it (`docker rm -f <name>`) immediately before the `docker run` —
silently when there was nothing, one INFO line when there was, and a removal docker refused is left
to the `run` to report. A `Launch` therefore never fails on `The container name … is already in
use`. A run the host *adopted* is unaffected: it is the same attempt carried on, its step was already
launched, and the host does not launch it a second time. This is the one removal that does not read
the container's last output first — the attempt it belonged to was already given up.

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

**On the EDGE plane, `Ack.registryMirrors` is what actually rewrites a `FROM`.** The platform's own
Dockerfiles commit the MACHINE spellings (`mirror.dev.localhost:8080`, `registry.dev.localhost:8080`,
…) — what the platform's own buildkitd rewrites to its internal aliases — and a remote runner cannot
resolve those. qits-ci knows both spellings, so its `Ack` carries the map from the committed spelling
to the PUBLIC name (`mirror.qits.<domain>`, `registry.qits.<domain>`, …) a runner outside the
platform's network can actually dial, and `Launcher.onAck` merges it over
`QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS`, overriding any entry for the same source host — the two
mechanisms answer different questions (the operator's env var is for a runner sharing the platform
host's own `qits-net`; the `Ack` map is for one that is not). The merged `BuildPlane`'s stamp differs
from the unmerged one whenever the map actually changes something, so `ensure` swaps the builder on
the *next* build that needs one — never a build already in flight — the same guarantee a bumped image
pin or a changed CA bundle path already gets. The public targets are always HTTPS with a
publicly-issued certificate, so they are never added to the `http = true` set; only what
`QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES` names is. One line to the runner's log per actual change —
`ci-runner builder mirrors from qits-ci: <n> registries` — never one per `Ack`, since qits-ci resends
`Ack` for reasons (a slots change) that carry the same map every time.

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

**What actually rewrites a `FROM` line on the EDGE plane comes from qits-ci, on connect.** The
platform's own committed Dockerfiles name the MACHINE spellings (`mirror.dev.localhost:8080`,
`registry.dev.localhost:8080`, …) — what the platform's own buildkitd rewrites to its internal
aliases, and what nothing outside the platform's network can resolve. qits-ci is the one side that
knows both spellings, so its `Ack` carries the committed-spelling → public-name map
(`Ack.registryMirrors`), and the runner merges it into the builder's `buildkitd.toml` the same way an
operator's `QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS` entry would be — see "The build plane" above.
Nobody on the EDGE plane configures this by hand.

**Credentials for a rewritten mirror ride the step's own login, not a builder secret.** buildkitd
never holds a registry credential of its own; a resolver that needs one asks the client session
`buildctl` opened for the build — the one over which it dials this builder — for credentials keyed
to the exact host it is about to contact. After a `registry.*` mirror rewrite that host is the PUBLIC
name (`mirror.qits.<domain>`, `registry.qits.<domain>`), and the step's own `DOCKER_CONFIG` (from
`QITS_CI_REGISTRY_AUTH_CONFIG`, built from the run's token — see "The step image is pulled under the
launch's own login" above) already carries an entry for exactly that host, because that is the host
the step's own `buildctl`/`docker buildx` invocation was always going to dial. So the mirror rewrite
needs nothing added to `buildkitd.toml` or to the builder container's own environment — the runner
never writes a credential there, and this README is where that fact is recorded rather than in code
that would otherwise look like an oversight.

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
