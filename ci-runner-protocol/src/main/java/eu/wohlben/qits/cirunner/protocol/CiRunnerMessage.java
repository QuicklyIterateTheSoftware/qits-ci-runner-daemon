package eu.wohlben.qits.cirunner.protocol;

/**
 * The sealed set of runner control-socket messages. {@link CiRunnerCodec} encodes any of these to a
 * framework-free {@code Map} and decodes one back, so both sides can {@code switch} exhaustively
 * over the received type.
 */
public sealed interface CiRunnerMessage
    permits Hello,
        Reserve,
        Launched,
        LaunchFailed,
        Reaped,
        Heartbeat,
        Ack,
        Backlog,
        Take,
        Nothing,
        Launch,
        Reap,
        Cancel,
        Released,
        Upgrade,
        Retire {}
