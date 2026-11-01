/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import java.io.IOException;

/**
 * Optional observation of the SDK-operated process streams. Stdout and stderr are
 * observed before decoding; stdin is observed after an encoded underlying write returns
 * successfully. A write does not prove child consumption, and a failed write may have
 * delivered an unknown prefix. Chunks are arbitrary I/O boundaries, not protocol frames.
 *
 * <p>
 * Each invocation receives a fresh array that the observer owns and may retain or modify.
 * Calls are synchronous and ordered within each stream, but different streams may call
 * concurrently. There is no causal ordering guarantee across streams. Observers should
 * return promptly and must not call transport lifecycle methods from a callback.
 *
 * <p>
 * The caller owns recording destinations, indexing, flushing and closing. The SDK never
 * closes the observer or its resources. An IOException (or wrapped RuntimeException)
 * stops the affected I/O loop and is retained by
 * {@link StreamingTransport#getSessionError()}, including during shutdown. Facade users
 * should latch recording failures in their observer and check that latch and their own
 * flush/close results before declaring capture complete. A model response alone does not
 * establish complete or durable capture. Cancellation may truncate output; this hook
 * supplies no recording-completeness receipt.
 */
@FunctionalInterface
public interface TransportByteObserver {

	/** Direction relative to the child process. */
	enum Stream {

		STDIN, STDOUT, STDERR

	}

	/**
	 * Accepts one nonempty chunk of original bytes.
	 * @param stream the child process stream
	 * @param bytes a fresh observer-owned array
	 * @throws IOException if recording fails
	 */
	void onBytes(Stream stream, byte[] bytes) throws IOException;

}
