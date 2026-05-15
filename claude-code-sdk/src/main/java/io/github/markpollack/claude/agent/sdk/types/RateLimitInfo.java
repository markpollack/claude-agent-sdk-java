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
 * Rate limit information from a {@code rate_limit_event} server-sent event. Contains the
 * current rate limit status, quota type, and reset timing.
 */
public record RateLimitInfo(@JsonProperty("status") String status,

		@JsonProperty("resetsAt") long resetsAt,

		@JsonProperty("rateLimitType") String rateLimitType,

		@JsonProperty("overageStatus") String overageStatus,

		@JsonProperty("overageDisabledReason") String overageDisabledReason,

		@JsonProperty("isUsingOverage") boolean isUsingOverage) {

	/**
	 * Whether the request was allowed through the rate limit.
	 */
	public boolean isAllowed() {
		return "allowed".equals(status);
	}

}
