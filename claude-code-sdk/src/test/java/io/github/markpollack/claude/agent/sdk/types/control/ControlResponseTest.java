/*
 * Copyright 2025 Mark Pollack
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

package io.github.markpollack.claude.agent.sdk.types.control;

import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlResponseTest {

	private final ObjectMapper writer = new ObjectMapper();

	private final ObjectMapper strictReader = new ObjectMapper(
			JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

	@Test
	void successResponseIsStrictJsonAndRetainsItsTypedPayload() throws Exception {
		var response = ControlResponse.success("hook-1", Map.of("permission_decision", "allow"));
		String json = writer.writeValueAsString(response);

		assertThat(strictReader.readTree(json)).isEqualTo(strictReader.readTree("""
				{"type":"control_response","response":{"subtype":"success","request_id":"hook-1",
				"response":{"permission_decision":"allow"}}}
				"""));
		assertThat(strictReader.readValue(json, ControlResponse.class)).isEqualTo(response);
	}

	@Test
	void errorResponseIsStrictJsonAndRetainsItsTypedPayload() throws Exception {
		var response = ControlResponse.error("hook-2", "test denial");

		String json = writer.writeValueAsString(response);

		assertThat(strictReader.readTree(json)).isEqualTo(strictReader.readTree("""
				{"type":"control_response","response":{"subtype":"error","request_id":"hook-2",
				"error":"test denial"}}
				"""));
		assertThat(strictReader.readValue(json, ControlResponse.class)).isEqualTo(response);
	}

	@Test
	void successWithoutDataStillOmitsNullPayloadAndRetainsDiscriminator() throws Exception {
		var response = ControlResponse.success("hook-3", null);

		String json = writer.writeValueAsString(response);

		assertThat(strictReader.readTree(json)).isEqualTo(strictReader.readTree("""
				{"type":"control_response","response":{"subtype":"success","request_id":"hook-3"}}
				"""));
		assertThat(strictReader.readValue(json, ControlResponse.class)).isEqualTo(response);
	}

}
