package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: the answer to {@link Reserve} when the claim succeeded. The run is now {@code
 * RUNNING} and recorded against this runner, and it holds one of the runner's slots until {@link
 * Released}.
 *
 * <p>The repository coordinates are for the runner's log and nothing else — the runner clones
 * nothing. qits-ci keeps driving the run and sends each step as a {@link Launch}.
 *
 * <p>{@code runId} is a String because that is qits-ci's run id type ({@code CiRun.id}, a UUID in
 * its text form): a {@code java.util.UUID} here would be a parse on both ends and a second spelling
 * of one value.
 */
public record Take(String runId, String repoName, String branch, String sha)
    implements CiRunnerMessage {}
