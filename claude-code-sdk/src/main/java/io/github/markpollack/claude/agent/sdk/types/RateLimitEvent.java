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

package io.github.markpollack.claude.agent.sdk.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Server-sent event carrying rate limit status. Emitted by the Claude CLI during
 * streaming sessions. Contains quota information and reset timing that callers can use for
 * proactive back-off.
 */
public record RateLimitEvent(@JsonProperty("type") String type,

		@JsonProperty("rate_limit_info") RateLimitInfo rateLimitInfo,

		@JsonProperty("uuid") String uuid,

		@JsonProperty("session_id") String sessionId) {

	/**
	 * Whether the request was allowed through the rate limit.
	 */
	public boolean isAllowed() {
		return rateLimitInfo != null && rateLimitInfo.isAllowed();
	}

}
