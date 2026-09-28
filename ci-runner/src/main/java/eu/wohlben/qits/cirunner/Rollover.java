package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Retire;
import eu.wohlben.qits.cirunner.protocol.Upgrade;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Self-update: a runner is a container, and becoming another version is starting another container
 * and getting out of its way. There is no supervisor process — docker's restart policy supervises,
 * and the runner already holds the host's docker socket.
 *
 * <pre>
 *   Upgrade{version, image, sha256}  → drain (no more Reserve)          [RunnerMain]
 *                                    → pull image under a throwaway login, check its digest
 *   held runs reach 0                → docker run the successor: own parameters, new image/name/label
 *   Retire within the watch          → docker update --restart=no self, close, exit 0
 *   no Retire within the watch       → docker rm -f the successor, stay draining, retry later
 * </pre>
 *
 * and, in the successor, after its first {@code Ack}: remove the exited predecessors.
 *
 * <p><b>The rollback is the watch.</b> A successor that cannot connect — a bad image, a protocol it
 * gets wrong, a host that refuses it — never says {@code Hello} in the pinned version, so the host
 * never sends {@code Retire}, and the old process removes it and carries on draining. Nothing is
 * stopped before the new one has proven itself on the wire; the price is that a failing rollover
 * leaves the runner holding no slots (the host gives a draining connection none) until one succeeds.
 *
 * <p><b>Retries back off to every ten minutes.</b> A registry that is down, an image that is not
 * pushed yet, a docker that refuses: none of those is fixed by asking again at once, and all of them
 * fix themselves eventually.
 *
 * <p>All docker work runs on one thread of its own, so a pull that takes a minute holds up no frame.
 */
public final class Rollover {

  private static final Logger LOG = Logger.getLogger(Rollover.class);

  /** Timings. {@link #defaults} is production's; the suite shrinks every one. */
  public record Settings(
      long firstRetryMillis,
      long maxRetryMillis,
      long successorTimeoutMillis,
      long predecessorWaitMillis,
      long pollMillis,
      long bearerTimeoutMillis) {

    /** 30 s doubling to 10 min; the watch as configured; a minute for a predecessor to leave. */
    public static Settings defaults(long successorTimeoutSeconds) {
      return new Settings(30_000, 600_000, successorTimeoutSeconds * 1000, 60_000, 1_000, 30_000);
    }
  }

  /**
   * Built by {@link RunnerMain} once it has a bearer — the registry login is the runner's own access
   * token — and knows its slot state.
   */
  @FunctionalInterface
  public interface Factory {
    Rollover create(Supplier<Future<String>> bearer, IntSupplier held);
  }

  private final Docker docker;
  private final String dockerBinary;
  private final String runnerId;
  private final String ownVersion;
  private final Optional<String> self;
  private final Settings settings;
  private final Supplier<Future<String>> bearer;
  private final IntSupplier held;
  private final AtomicBoolean predecessorsSwept = new AtomicBoolean();

  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "ci-runner-rollover");
            thread.setDaemon(true);
            return thread;
          });

  // Guarded by `this`.
  private Upgrade target;
  private boolean pulled;
  private String successor;
  private ScheduledFuture<?> watch;
  private int failures;

  public Rollover(
      Docker docker,
      String dockerBinary,
      String runnerId,
      String ownVersion,
      Optional<String> self,
      Settings settings,
      Supplier<Future<String>> bearer,
      IntSupplier held) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.runnerId = runnerId;
    this.ownVersion = ownVersion;
    this.self = self;
    this.settings = settings;
    this.bearer = bearer;
    this.held = held;
  }

  /**
   * The host asked for {@code upgrade.version()}. The caller has already stopped reserving; this
   * starts the pull and, once no run is held, the successor. A repeat of the version already under
   * way — the host sends one on every reconnect — changes nothing.
   */
  public void upgrade(Upgrade upgrade) {
    try {
      RunnerArgv.requireVersion(upgrade.version());
      RunnerArgv.requireImage(upgrade.image());
    } catch (IllegalArgumentException refused) {
      LOG.warnf("ci-runner cannot act on an Upgrade: %s; it stays draining", refused.getMessage());
      return;
    }
    if (upgrade.version().equals(ownVersion)) {
      LOG.infof("ci-runner was asked to upgrade to %s, which it already is", ownVersion);
      return;
    }
    synchronized (this) {
      if (target != null && target.version().equals(upgrade.version())) {
        return;
      }
      if (successor != null) {
        LOG.infof(
            "ci-runner ignored an upgrade to %s: its successor %s is taking over",
            upgrade.version(), successor);
        return;
      }
      target = upgrade;
      pulled = false;
      failures = 0;
    }
    LOG.infof(
        "ci-runner upgrade to %s requested; draining %d held run(s)",
        upgrade.version(), held.getAsInt());
    if (self.isEmpty()) {
      LOG.warn(
          "ci-runner is not running in a docker container, so it cannot start a successor; it"
              + " stays draining until it is replaced by hand");
      return;
    }
    poke();
  }

  /** Something that could let the rollover proceed happened — a run was released, a socket closed. */
  public void poke() {
    if (!executor.isShutdown()) {
      executor.execute(this::attempt);
    }
  }

  /**
   * The host says a runner of the pinned version has taken over. Answers whether this process should
   * now leave: yes when no upgrade was ever asked for (an operator's retirement) or when this
   * process's successor is running; no when an upgrade is under way with no successor of it alive —
   * the watch already removed it, or it was never started — because the connection the host saw is
   * then not this runner's successor, and leaving would leave the host with nothing.
   */
  public boolean retire(Retire retire) {
    synchronized (this) {
      if (target == null) {
        LOG.infof("ci-runner retired by the host (%s)", retire.reason());
        return true;
      }
      if (successor != null) {
        if (watch != null) {
          watch.cancel(false);
          watch = null;
        }
        LOG.infof(
            "ci-runner retired by the host (%s); %s has taken over", retire.reason(), successor);
        return true;
      }
      LOG.warnf(
          "ci-runner ignored a Retire (%s): its upgrade to %s has no successor running",
          retire.reason(), target.version());
      return false;
    }
  }

  /**
   * The exit path's docker half: take this container's restart policy away, so the exit that follows
   * is its last. A failure is logged and the exit goes ahead anyway — docker restarting this
   * container is then the one consequence, and the successor's predecessor sweep removes it.
   */
  public void leave() {
    if (self.isEmpty()) {
      return;
    }
    Docker.Result updated = docker.run(RunnerArgv.noRestart(dockerBinary, self.get()));
    if (!updated.ok()) {
      LOG.warnf(
          "ci-runner could not take its own restart policy away: %s", updated.detail());
    }
  }

  /**
   * Once per process, after its first {@code Ack}: remove this runner's containers of any other
   * version — the predecessor that just handed over, and any older one a failed exit left behind.
   * A predecessor still running is given {@link Settings#predecessorWaitMillis} to exit on its own
   * {@code Retire} first. Never this process's own container: its version label is this process's
   * version, and it is also recognised by id.
   */
  public void removePredecessors() {
    if (!predecessorsSwept.compareAndSet(false, true)) {
      return;
    }
    Docker.Result listed = docker.run(RunnerArgv.psProcess(dockerBinary, runnerId));
    if (!listed.ok()) {
      LOG.warnf("ci-runner could not list its predecessors: %s", listed.detail());
      return;
    }
    for (String line : listed.stdout().split("\\R")) {
      String[] fields = line.strip().split("\\|", -1);
      if (fields.length < 3 || fields[0].isBlank()) {
        continue;
      }
      String id = fields[0].strip();
      String version = fields[1].strip();
      String state = fields[2].strip();
      if (version.equals(ownVersion) || self.map(s -> SelfContainer.same(s, id)).orElse(false)) {
        continue;
      }
      try {
        if (state.equals("running") || state.equals("restarting")) {
          state = awaitExit(id);
        }
        Docker.Result gone = docker.run(RunnerArgv.rm(dockerBinary, id));
        if (gone.ok()) {
          LOG.infof("ci-runner removed its predecessor %s (%s, %s)", id, version, state);
        } else {
          LOG.warnf("ci-runner could not remove its predecessor %s: %s", id, gone.detail());
        }
      } catch (IllegalArgumentException notAnId) {
        LOG.warnf("ci-runner ignored a line docker ps answered: %s", notAnId.getMessage());
      }
    }
  }

  /** Stop the rollover thread. */
  public void shutdown() {
    executor.shutdownNow();
  }

  private String awaitExit(String id) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.predecessorWaitMillis());
    String state = "running";
    while (System.nanoTime() < deadline) {
      Docker.Result answered = docker.run(RunnerArgv.state(dockerBinary, id));
      state = answered.ok() ? answered.stdout().strip() : "gone";
      if (!state.equals("running") && !state.equals("restarting")) {
        return state;
      }
      try {
        Thread.sleep(settings.pollMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return state;
      }
    }
    LOG.warnf(
        "ci-runner's predecessor %s is still %s after %ds; removing it anyway",
        id, state, settings.predecessorWaitMillis() / 1000);
    return state;
  }

  private void attempt() {
    Upgrade upgrade;
    boolean havePulled;
    synchronized (this) {
      if (target == null || successor != null || self.isEmpty()) {
        return;
      }
      upgrade = target;
      havePulled = pulled;
    }
    if (!havePulled) {
      Optional<String> failure = pull(upgrade);
      if (failure.isPresent()) {
        retry(upgrade, failure.get());
        return;
      }
      synchronized (this) {
        pulled = true;
      }
      LOG.infof("ci-runner pulled %s", upgrade.image());
    }
    int holding = held.getAsInt();
    if (holding > 0) {
      // Released and a closed socket both poke; the last run's Released is what brings this back.
      LOG.debugf("ci-runner waits for %d held run(s) before starting %s", holding, upgrade.version());
      return;
    }
    Optional<String> failure = startSuccessor(upgrade);
    if (failure.isPresent()) {
      retry(upgrade, failure.get());
    }
  }

  private void retry(Upgrade upgrade, String why) {
    long delay;
    synchronized (this) {
      if (target != upgrade) {
        return;
      }
      failures++;
      long doubled = settings.firstRetryMillis() << Math.min(failures - 1, 20);
      delay = Math.min(settings.maxRetryMillis(), Math.max(1, doubled));
    }
    LOG.warnf(
        "ci-runner could not roll over to %s: %s; still draining, retrying in %ds",
        upgrade.version(), why, Math.max(1, delay / 1000));
    if (!executor.isShutdown()) {
      executor.schedule(this::attempt, delay, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Pull the image under this runner's own login, and check its digest when the host sent one.
   *
   * <p><b>The login is a throwaway {@code docker --config} directory</b>, the same arrangement as a
   * launch's pull (see {@link Launcher}): {@code auths[<registry host>].auth} is {@code
   * base64("token:" + bearer)}, the directory 0700 and the file 0600 from creation, deleted in the
   * {@code finally}. The host's docker config is never written, the bearer is never logged, and a
   * failure's detail has both it and its base64 taken out.
   */
  Optional<String> pull(Upgrade upgrade) {
    String token;
    try {
      token =
          bearer
              .get()
              .toCompletionStage()
              .toCompletableFuture()
              .get(settings.bearerTimeoutMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of("interrupted while minting its registry login");
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      return Optional.of("could not mint its registry login: " + cause.getMessage());
    }
    String auth =
        Base64.getEncoder().encodeToString(("token:" + token).getBytes(StandardCharsets.UTF_8));
    String document =
        new JsonObject()
            .put(
                "auths",
                new JsonObject()
                    .put(registryHost(upgrade.image()), new JsonObject().put("auth", auth)))
            .encode();
    Path configDir = null;
    try {
      try {
        configDir = Launcher.stageConfig(document);
      } catch (IOException | UnsupportedOperationException staging) {
        return Optional.of(
            "could not stage its registry login: " + staging.getClass().getSimpleName());
      }
      Docker.Result pulled =
          docker.run(RunnerArgv.pull(dockerBinary, configDir, upgrade.image()));
      if (!pulled.ok()) {
        return Optional.of(
            "docker pull "
                + upgrade.image()
                + " failed: "
                + pulled.detail().replace(token, "[redacted]").replace(auth, "[redacted]"));
      }
    } finally {
      Launcher.removeConfig(configDir);
    }
    return verifyDigest(upgrade);
  }

  private Optional<String> verifyDigest(Upgrade upgrade) {
    if (upgrade.sha256() == null || upgrade.sha256().isBlank()) {
      return Optional.empty();
    }
    String expected = upgrade.sha256().strip().toLowerCase(Locale.ROOT);
    if (!expected.startsWith("sha256:")) {
      expected = "sha256:" + expected;
    }
    Docker.Result digests = docker.run(RunnerArgv.repoDigests(dockerBinary, upgrade.image()));
    if (!digests.ok()) {
      return Optional.of("could not read the pulled image's digest: " + digests.detail());
    }
    JsonArray known;
    try {
      known = new JsonArray(digests.stdout().strip());
    } catch (RuntimeException e) {
      return Optional.of("docker answered no digest list for " + upgrade.image());
    }
    for (Object digest : known) {
      if (digest instanceof String repoDigest && repoDigest.endsWith("@" + expected)) {
        return Optional.empty();
      }
    }
    return Optional.of(
        "the pulled image is not " + expected + " (docker knows it as " + known.encode() + ")");
  }

  private Optional<String> startSuccessor(Upgrade upgrade) {
    String selfId = self.orElseThrow();
    Docker.Result inspected = docker.run(RunnerArgv.inspectSelf(dockerBinary, selfId));
    if (!inspected.ok()) {
      return Optional.of("could not inspect its own container: " + inspected.detail());
    }
    SelfSpec spec;
    List<String> argv;
    try {
      String imageId = new JsonObject(inspected.stdout().strip()).getString("image", "");
      Docker.Result image = docker.run(RunnerArgv.imageConfig(dockerBinary, imageId));
      if (!image.ok()) {
        return Optional.of("could not inspect its own image: " + image.detail());
      }
      spec = SelfSpec.parse(inspected.stdout(), image.stdout());
      argv =
          RunnerArgv.runSuccessor(
              dockerBinary, runnerId, upgrade.version(), upgrade.image(), spec);
    } catch (RuntimeException e) {
      return Optional.of("could not derive its successor's parameters: " + e.getMessage());
    }
    String name = RunnerArgv.containerName(runnerId, upgrade.version());
    synchronized (this) {
      // Recorded before the run, not after: the successor can say Hello — and the host send this
      // process Retire — before `docker run -d` has even returned here.
      successor = name;
    }
    // A leftover under the name — an earlier attempt that docker created and could not start — is
    // this runner's by the naming contract, and would make the run below a name conflict.
    docker.run(RunnerArgv.rm(dockerBinary, name));
    Docker.Result started = docker.run(argv);
    if (!started.ok()) {
      synchronized (this) {
        successor = null;
      }
      return Optional.of("docker run of its successor failed: " + started.detail());
    }
    synchronized (this) {
      if (successor != null && watch == null) {
        watch =
            executor.schedule(
                this::timedOut, settings.successorTimeoutMillis(), TimeUnit.MILLISECONDS);
      }
    }
    LOG.infof(
        "ci-runner started its successor %s on %s; waiting up to %ds for it to take over",
        name, upgrade.image(), settings.successorTimeoutMillis() / 1000);
    return Optional.empty();
  }

  private void timedOut() {
    String name;
    Upgrade upgrade;
    synchronized (this) {
      if (successor == null) {
        return;
      }
      name = successor;
      upgrade = target;
      successor = null;
      watch = null;
    }
    Docker.Result gone = docker.run(RunnerArgv.rm(dockerBinary, name));
    if (!gone.ok()) {
      LOG.warnf("ci-runner could not remove its successor %s: %s", name, gone.detail());
    }
    retry(
        upgrade,
        "its successor "
            + name
            + " did not take over within "
            + settings.successorTimeoutMillis() / 1000
            + "s and was removed");
  }

  /**
   * The registry an image reference names — the {@code auths} key docker looks its login up by. A
   * first path segment with a dot, a colon or {@code localhost} is a host; anything else is Docker
   * Hub, whose key is the historical index url.
   */
  static String registryHost(String image) {
    int slash = image.indexOf('/');
    if (slash > 0) {
      String first = image.substring(0, slash);
      if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
        return first;
      }
    }
    return "https://index.docker.io/v1/";
  }

  /** For the suite: the successor being watched, or null. */
  synchronized String successor() {
    return successor;
  }
}
