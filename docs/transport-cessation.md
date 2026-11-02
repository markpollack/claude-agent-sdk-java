# Observing transport shutdown

`close()` starts one bounded cleanup attempt. Its original seven-second allowance covers
process termination, reader draining and resource cleanup; another call does not renew
that allowance. Returning from `close()` or seeing `isConnected() == false` is not proof
that every resource terminated.

Both client interfaces expose an optional immutable observation, including after close:

```java
client.close();
var resources = client.transportResources().orElseThrow();
if (!resources.observedResourcesTerminated()) {
    // Retain ownership and surface uncertainty. Do not reuse this transport.
    System.err.println(resources.issues());
}
```

For the reactive client, first subscribe to and await `client.close()`. The direct
`StreamingTransport` equivalent is `resourceSnapshot()`.

The snapshot identifies one SDK transport with `connectionId`, and retains its observed
root and descendant process handles, process start times where available, and current
`ALIVE`, `EXITED` or `UNKNOWN` states. Discovery failures remain explicit. It also reports
acquisition and close-worker progress, each stdout/stderr pump's completion and actual
EOF, owned executor termination, stream closing and temporary MCP-file cleanup.

`observedResourcesTerminated()` concerns that **observed resource inventory**. This is
not operating-system containment: a process that detached before discovery may be absent.
It does not establish native protocol completion, successful cancellation, safe session
reuse, successful byte capture, or archive completeness. In particular, a reader may
finish after an I/O or observer failure without observing EOF. Check `stdout().eof()`,
`stderr().eof()` and recording failures independently where the application requires them.

Cleanup targets only retained owned process handles, attempts graceful termination and
then forced termination, and checks actual exit. Uncooperative callbacks or stream closes
cannot extend the caller's allowance; unresolved resources remain visible and may settle
later. The same snapshot object never changes, so obtain a new snapshot to observe later
settlement. No provider work is replayed. Partial acquisition failures preserve their
initiating exception while disposing any acquired process. A failed facade connection
cannot replace an earlier unsettled transport with a new one.

An empty optional means no transport inventory is exposed. Third-party implementations
of the client interfaces inherit that conservative default. It is not an empty, successfully
terminated inventory. The byte observer and existing close/exception signatures are unchanged.
