# qits-ci-runner-daemon — working notes

Read `README.md` first: it defines what the runner does, the conversation, and the install-script
contract. This file is the working conventions on top of it. It deliberately mirrors
qits-ci-daemon's `AGENTS.md`; where the two repositories differ, it says why.

## The rules that shape everything

**A clone of this repo alone builds and tests green.** No monorepo, no docker, no prior `mvn
install` elsewhere, no credentials, no network. `./mvnw verify` and `sh
scripts/test-install-contract.sh` are the gate. That is why the protocol module is self-contained,
why the suite points the runner at a shell script that answers like docker instead of a docker, and
why the install test stubs `curl` rather than starting a server.

**It compiles to a fully static musl GraalVM native image**, with qits-ci-daemon's toolchain and for
its reason, sharpened: a runner host is whatever Linux a person had. Every dependency is a decision
about image size and about whether it links anything glibc-only. `io.vertx.core.json` instead of
Jackson, vertx-core's `HttpClient` instead of a REST client, `ProcessBuilder` instead of a process
library or a docker SDK.

**An empty `defaultValue` is not a default.** Settings are `Optional<String>` in `Main` and parsed
by `RunnerEnv`, which turns a missing or bad value into one sentence naming the *variable* and exit
2. The suite cannot see a SmallRye startup failure; the release request's smoke probe (the binary in
a container with no environment must exit 2) can.

**A healthy runner never exits.** This is the one deliberate inversion of qits-ci-daemon's shape: a
step daemon has one step and always exits; a runner is its host's reason to be on the platform, is
supervised by systemd with `Restart=always`, and reconnects forever. Every exit code in `ExitCode` is
a reason the journal should name.

## Module conventions

`eu.wohlben.qits.cirunner.*`, one package per module, no split packages. The protocol module is
**framework-free**: plain records, plain constructors, no annotations, no dependency.

**`Main` is the only CDI bean.** `RunnerMain`, `ControlSocket`, `Registration`, `Bearer`,
`Launcher`, `Reaper`, `BootSweep`, `BuildPlane` and `Reservations` are plain classes taking
everything as constructor arguments, which is what lets `RunnerMainTest` drive the whole runner
against a real socket. A class below `Main` cannot read configuration; do not reach for
`ConfigProvider`.

**Every docker call goes through `Docker`, under the deadline.** Never a bare `ProcessBuilder` on
docker elsewhere: a docker CLI that hangs would hold a slot or an answer forever, and the host cannot
see why.

**Every docker argv is built in `RunnerArgv`** (or, for the one privileged builder, `BuildPlane`'s
fixed argv), as a pure function, and asserted element for element. qits-containers' `DockerArgv` is
the reference. `--rm` appears nowhere.

## Testing

Plain JUnit 5. **No Mockito, no `@QuarkusTest`.** Real processes and real sockets are preferred over
seams: `FakeDocker` writes a shell script into the test's temp directory and the production
`Docker.forking` runs it; `FakeHost` is a real Vert.x server serving the register door, the token
endpoint and the control socket. Anything OS-dependent is `@EnabledOnOs(OS.LINUX)`.

Test names are sentences describing the behaviour (`aLaunchDockerRefusesIsAnsweredLaunchFailedWithItsWords`).

The install contract is tested by `scripts/test-install-contract.sh`, and it is only as good as its
mutations: when you change it, break the fixture the way the new assertion guards against and watch
it fail.

## Adding a control-socket message

1. A record in `ci-runner-protocol`, added to the `CiRunnerMessage` permits list.
2. `Type` and `Field` constants — never a bare string at a call site.
3. Encode and decode arms in `CiRunnerCodec`, and the round-trip list in `CiRunnerCodecTest` (its
   permitted-subclass count will fail until you do).
4. Bump `CiRunnerProtocol.CAPABILITY_VERSION` only if a peer must branch on it. A new *field* is not
   a bump: the codec ignores unknown fields in both directions.
5. **Release this repository, then bump the dependency in qits-ci-service** and handle the new case
   there. This repo is the protocol's only author; the published jar is what enforces it.

## Untrusted input

The `WorkloadSpec` arrives over a socket and becomes an argv to a root-equivalent daemon. So:

- **The shape is the boundary.** No free-form argv, no volume, no host path; the one bind is the
  `hostDockerSocket` boolean. A capability a spec cannot express is a change to the record, reviewed
  as one.
- **Belts at the argv.** Every value that reaches an element is checked against its charset in
  `RunnerArgv`; a refusal is a `LaunchFailed` before any docker call.
- **The runner's own label namespace (`qits.ci.runner`, `qits.ci.runner.*`) is refused in a spec**,
  because a forged one would steer the boot sweep.
- **The boot sweep and a cancel select by the runner's own label**, never host-wide.
- **Secrets stay put.** The registration token is read once and never logged; `client.json` is 0600
  from creation; the access token lives in memory only. A step's environment carries credentials:
  never log a spec's env.

## Formatting

`google-java-format`, 100 columns, two-space indent. Javadoc explains *why* — the tradeoff, the
alternative rejected, the failure it prevents. What the code does is the code's job.
