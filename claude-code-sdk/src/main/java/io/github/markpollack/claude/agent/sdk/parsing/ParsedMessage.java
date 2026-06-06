/*
 * Copyright 2025 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.markpollack.claude.agent.sdk.parsing;

import io.github.markpollack.claude.agent.sdk.types.Message;
import io.github.markpollack.claude.agent.sdk.types.RateLimitEvent;
import io.github.markpollack.claude.agent.sdk.types.control.ControlRequest;
import io.github.markpollack.claude.agent.sdk.types.control.ControlResponse;

/**
 * Represents a parsed message from Claude CLI output. In bidirectional mode, the CLI can
 * send either regular messages (user, assistant, system, result) or control requests.
 *
 * <p>
 * This sealed interface provides type-safe handling of both cases:
 * </p>
 *
 * <pre>{@code
 * ParsedMessage parsed = parser.parse(json);
 * switch (parsed) {
 *     case ParsedMessage.RegularMessage r -> handleMessage(r.message());
 *     case ParsedMessage.Control c -> handleControlRequest(c.request());
 * }
 * }</pre>
 */
public sealed interface ParsedMessage permits ParsedMessage.RegularMessage, ParsedMessage.Control,
		ParsedMessage.ControlResponseMessage, ParsedMessage.RateLimitEventMessage, ParsedMessage.EndOfStream {

	/**
	 * Check if this is a regular message (user, assistant, system, result).
	 */
	default boolean isRegularMessage() {
		return this instanceof RegularMessage;
	}

	/**
	 * Check if this is a control request.
	 */
	default boolean isControlRequest() {
		return this instanceof Control;
	}

	/**
	 * Check if this is a control response.
	 */
	default boolean isControlResponse() {
		return this instanceof ControlResponseMessage;
	}

	/**
	 * Get as regular message, or null if this is a control request.
	 */
	default Message asMessage() {
		return this instanceof RegularMessage r ? r.message() : null;
	}

	/**
	 * Get as control request, or null if this is a regular message.
	 */
	default ControlRequest asControlRequest() {
		return this instanceof Control c ? c.request() : null;
	}

	/**
	 * Get as control response, or null if this is not a control response.
	 */
	default ControlResponse asControlResponse() {
		return this instanceof ControlResponseMessage cr ? cr.response() : null;
	}

	/**
	 * Check if this is a rate limit event.
	 */
	default boolean isRateLimitEvent() {
		return this instanceof RateLimitEventMessage;
	}

	/**
	 * Get as rate limit event, or null if this is not a rate limit event.
	 */
	default RateLimitEvent asRateLimitEvent() {
		return this instanceof RateLimitEventMessage rle ? rle.event() : null;
	}

	/**
	 * Wrapper for regular messages (type=user, assistant, system, result).
	 *
	 * @param message the typed message
	 * @param rawJson the raw JSON line this message was parsed from, or null when the
	 * message was constructed programmatically (e.g. in tests). This is a lossless escape
	 * hatch for wire fields not yet modeled by the typed API — it is NOT a semantic API
	 * guarantee, and consumers must not treat raw blobs as the primary interface. The CLI
	 * wire format evolves; fields recovered from rawJson should be promoted to typed
	 * accessors once stable.
	 */
	record RegularMessage(Message message, String rawJson) implements ParsedMessage {
		public RegularMessage {
			if (message == null) {
				throw new IllegalArgumentException("message must not be null");
			}
		}

		/**
		 * Creates a RegularMessage without raw JSON (programmatic construction).
		 */
		public RegularMessage(Message message) {
			this(message, null);
		}

		/**
		 * Factory method for creating a RegularMessage without raw JSON.
		 */
		public static RegularMessage of(Message message) {
			return new RegularMessage(message, null);
		}

		/**
		 * Factory method for creating a RegularMessage retaining the raw JSON line it was
		 * parsed from.
		 */
		public static RegularMessage of(Message message, String rawJson) {
			return new RegularMessage(message, rawJson);
		}
	}

	/**
	 * Wrapper for control protocol requests (type=control_request).
	 */
	record Control(ControlRequest request) implements ParsedMessage {
		public Control {
			if (request == null) {
				throw new IllegalArgumentException("request must not be null");
			}
		}

		/**
		 * Factory method for creating a Control message.
		 */
		public static Control of(ControlRequest request) {
			return new Control(request);
		}
	}

	/**
	 * Wrapper for control protocol responses (type=control_response). These are responses
	 * from the CLI to control requests we sent (e.g., interrupt, set_model,
	 * set_permission_mode).
	 */
	record ControlResponseMessage(ControlResponse response) implements ParsedMessage {
		public ControlResponseMessage {
			if (response == null) {
				throw new IllegalArgumentException("response must not be null");
			}
		}

		/**
		 * Factory method for creating a ControlResponseMessage.
		 */
		public static ControlResponseMessage of(ControlResponse response) {
			return new ControlResponseMessage(response);
		}
	}

	/**
	 * Wrapper for rate limit events (type=rate_limit_event). These are server-sent events
	 * carrying quota status and reset timing. Currently informational — the transport
	 * skips these, but callers can check them for proactive back-off.
	 */
	record RateLimitEventMessage(RateLimitEvent event) implements ParsedMessage {
		public RateLimitEventMessage {
			if (event == null) {
				throw new IllegalArgumentException("event must not be null");
			}
		}

		/**
		 * Factory method for creating a RateLimitEventMessage.
		 */
		public static RateLimitEventMessage of(RateLimitEvent event) {
			return new RateLimitEventMessage(event);
		}
	}

	/**
	 * Sentinel value used internally by MessageStreamIterator to signal end of stream.
	 * This should not be used by application code.
	 */
	record EndOfStream() implements ParsedMessage {

		/**
		 * Singleton instance.
		 */
		public static final EndOfStream INSTANCE = new EndOfStream();

	}

}
