/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Immutable observation of one transport's owned resources, including after close.
 * The process inventory contains the root and descendants observed while still related
 * to an owned process. It is not OS containment: a child that detaches before discovery
 * may be absent. Successful signal delivery and a disconnected client are not exit proof.
 * EOF is reported separately from reader termination and does not prove protocol success.
 *
 * @param connectionId stable identity of this transport instance
 * @param setupInProgress whether acquisition may still be completing
 * @param closeRequested whether the one bounded close attempt has started
 * @param closeWorkerFinished whether its cleanup worker has actually returned
 * @param closeBudget original total close allowance, never renewed by another call
 * @param processes immutable observed process inventory
 * @param enumerationSucceeded false when any process discovery failed
 * @param stdout stdout acquisition, pump completion and actual EOF
 * @param stderr stderr acquisition, pump completion and actual EOF
 * @param executorsTerminated whether all three owned executor services terminated
 * @param streamsClosed whether all acquired stream closes returned successfully
 * @param mcpConfigRemoved whether the owned temporary configuration is absent
 * @param issues immutable bounded-shutdown diagnostics, without source payloads
 */
public record TransportResourceSnapshot(String connectionId, boolean setupInProgress, boolean closeRequested,
        boolean closeWorkerFinished, Duration closeBudget, List<OwnedProcess> processes,
        boolean enumerationSucceeded, StreamStatus stdout, StreamStatus stderr, boolean executorsTerminated,
        boolean streamsClosed, boolean mcpConfigRemoved, List<String> issues) {

    public TransportResourceSnapshot {
        processes = List.copyOf(processes);
        issues = List.copyOf(issues);
    }

    /**
     * Positive cessation of this observed inventory only. Does not establish that no
     * child escaped discovery, successful capture, a native terminal boundary or reuse.
     * @return true only after every observed process and owned task/resource has settled
     */
    public boolean observedResourcesTerminated() {
        return closeRequested && closeWorkerFinished && !setupInProgress && enumerationSucceeded
                && processes.stream().allMatch(p -> p.state() == ProcessState.EXITED)
                && stdout.finished() && stderr.finished() && executorsTerminated && streamsClosed && mcpConfigRemoved;
    }

    /** Process liveness at snapshot time. */
    public enum ProcessState { ALIVE, EXITED, UNKNOWN }

    /**
     * An observed handle, retained across parent exit and close.
     * @param pid operating-system process identifier
     * @param startedAt process start time when supplied by the platform
     * @param root whether this is the process directly acquired by the transport
     * @param state positively observed liveness, or UNKNOWN on observation failure
     */
    public record OwnedProcess(long pid, Optional<Instant> startedAt, boolean root, ProcessState state) { }

    /**
     * Stream facts. A finished pump with eof=false may have failed or been interrupted.
     * @param acquired whether this stream was acquired
     * @param finished whether no reader task remains, including its final callback
     * @param eof whether the reader actually returned EOF
     */
    public record StreamStatus(boolean acquired, boolean finished, boolean eof) { }
}
