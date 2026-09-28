package eu.wohlben.qits.cirunner.protocol;

/**
 * The single source of truth for the runner control-socket wire contract's tags and field names.
 *
 * <p>Messages are JSON objects with a {@code "type"} discriminator ({@link Type}) and a set of
 * fields ({@link Field}); two of them nest an object ({@link Capabilities} in {@link Hello}, {@link
 * WorkloadSpec} in {@link Launch}), which every JSON layer bridges as a nested map. The records in
 * this package model each message's shape; qits-ci (de)serializes them with Jackson, the runner with
 * a Vert.x {@code JsonObject} — both through {@link CiRunnerCodec}, so a rename is caught in one
 * place.
 *
 * <p>The conversation, for as long as the runner's host is up:
 *
 * <pre>
 *   Hello → Ack{slots} → Backlog{queued}* …
 *   Reserve → Take{run} | Nothing           (one outstanding at a time)
 *   Launch → Launched | LaunchFailed         (per step, qits-ci driving)
 *   Reap → Reaped                            (per step)
 *   Cancel                                   (reap the whole run now)
 *   Released                                 (the run is closed; its slot is free)
 *   Upgrade → (drain) … Retire              (self-update; see Upgrade)
 * </pre>
 *
 * with {@link Heartbeat} underneath from connect to close.
 *
 * <p><b>Identity is not on the wire.</b> The runner presents a bearer minted from the idp client it
 * registered; qits-ci knows which runner this is before the first frame is read.
 */
public final class CiRunnerProtocol {

  /**
   * The capability version the runner announces in its {@link Hello} and the host echoes in its
   * {@link Ack}. Bumped when the wire contract changes in a way either side must branch on. A
   * runner that reads an {@link Ack} carrying a version it does not know exits nonzero rather than
   * guessing; docker's restart policy restarts it and its log says why.
   *
   * <p><b>Deliberately still 1 after {@link Upgrade} and {@link Retire} arrived.</b> An older runner
   * drops a frame of a type it does not know and carries on, which is all an unknown {@code Upgrade}
   * needs to be; a bump, on the other hand, would make every runner already installed exit on its
   * next {@code Ack} — exactly the runners self-update exists to reach.
   */
  public static final int CAPABILITY_VERSION = 1;

  private CiRunnerProtocol() {}

  /** The {@code "type"} discriminator values. */
  public static final class Type {
    // runner -> qits-ci
    public static final String HELLO = "hello";
    public static final String RESERVE = "reserve";
    public static final String LAUNCHED = "launched";
    public static final String LAUNCH_FAILED = "launchFailed";
    public static final String REAPED = "reaped";
    public static final String HEARTBEAT = "heartbeat";
    // qits-ci -> runner
    public static final String ACK = "ack";
    public static final String BACKLOG = "backlog";
    public static final String TAKE = "take";
    public static final String NOTHING = "nothing";
    public static final String LAUNCH = "launch";
    public static final String REAP = "reap";
    public static final String CANCEL = "cancel";
    public static final String RELEASED = "released";
    // qits-ci -> runner, self-update. FROZEN: see Upgrade and Retire.
    public static final String UPGRADE = "upgrade";
    public static final String RETIRE = "retire";

    private Type() {}
  }

  /** The JSON field names shared by both codecs. */
  public static final class Field {
    public static final String TYPE = "type";
    public static final String RUNNER_VERSION = "runnerVersion";
    public static final String CAPABILITY_VERSION = "capabilityVersion";
    public static final String CAPABILITIES = "capabilities";
    public static final String SLOTS = "slots";
    public static final String QUEUED = "queued";
    public static final String RUN_ID = "runId";
    public static final String REPO_NAME = "repoName";
    public static final String BRANCH = "branch";
    public static final String SHA = "sha";
    public static final String STEP_INDEX = "stepIndex";
    public static final String CONTAINER_ID = "containerId";
    public static final String CONTAINER_NAME = "containerName";
    public static final String DETAIL = "detail";
    public static final String WORKLOAD_SPEC = "workloadSpec";

    // Upgrade and Retire. FROZEN, with RUNNER_VERSION above and Upgrade's use of IMAGE below: see
    // Upgrade.
    public static final String VERSION = "version";
    public static final String SHA256 = "sha256";
    public static final String REASON = "reason";

    // Capabilities
    public static final String DOCKER = "docker";
    public static final String ARCH = "arch";
    public static final String OS = "os";
    public static final String LABELS = "labels";

    // WorkloadSpec (LABELS is shared with Capabilities; IMAGE with Upgrade, where it is FROZEN)
    public static final String IMAGE = "image";
    public static final String ENTRYPOINT = "entrypoint";
    public static final String ARGS = "args";
    public static final String ENV = "env";
    public static final String NETWORK = "network";
    public static final String EXTRA_HOSTS = "extraHosts";
    public static final String USER = "user";
    public static final String HOST_DOCKER_SOCKET = "hostDockerSocket";
    public static final String CAP_DROP_ALL = "capDropAll";
    public static final String NO_NEW_PRIVILEGES = "noNewPrivileges";
    public static final String MEMORY = "memory";
    public static final String MEMORY_SWAP = "memorySwap";
    public static final String PIDS_LIMIT = "pidsLimit";
    public static final String CPUS = "cpus";
    public static final String OOM_SCORE_ADJ = "oomScoreAdj";
    public static final String NAME = "name";
    public static final String BUILD_PLANE = "buildPlane";

    private Field() {}
  }
}
