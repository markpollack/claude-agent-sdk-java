/*
 * Copyright 2026 Mark Pollack
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

package io.github.markpollack.claude.agent.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.exceptions.ClaudeSDKException;
import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
import io.github.markpollack.claude.agent.sdk.hooks.HookRegistry;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput.HookSpecificOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives each client through hook registration and one {@code hook_callback} round trip
 * against a stub CLI, and checks what it writes to the CLI's stdin.
 *
 * <p>
 * The stub answers {@code initialize}, sends a PreToolUse {@code hook_callback} when the
 * user message arrives, and records everything the SDK writes to its stdin. A PreToolUse
 * deny must reach the CLI as the hook JSON output format, {@code hookSpecificOutput}
 * nested with camelCase keys and no {@code "continue": null}; anything else is discarded
 * by the CLI and the tool runs.
 * </p>
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("Hook callback responses on the wire")
class HookCallbackWireTest {

	private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String HOOK_REQUEST_ID = "hook-req-1";

	private static final String DENY_REASON = "branch changes are blocked";

	private static final String INITIALIZE_SUCCESS = """
			{"type":"control_response","response":{"subtype":"success","request_id":"%s","response":{}}}""";

	private static final String INITIALIZE_REFUSED = """
			{"type":"control_response","response":{"subtype":"error","request_id":"%s","error":"hooks rejected"}}""";

	private static final String CONTROL_SUCCESS = """
			{"type":"control_response","response":{"subtype":"success","request_id":"%s","response":{}}}""";

	private static final String CONTROL_REFUSED = """
			{"type":"control_response","response":{"subtype":"error","request_id":"%s","error":"request rejected"}}""";

	@TempDir
	Path tempDir;

	private Path recording;

	private String stubCli;

	private HookRegistry hooks;

	@BeforeEach
	void setUp() throws IOException {
		this.recording = tempDir.resolve("cli-stdin.jsonl");
		Files.createFile(recording);
		this.stubCli = writeStubCli(INITIALIZE_SUCCESS);
		this.hooks = new HookRegistry();
		hooks.registerPreToolUse("Bash",
				input -> HookOutput.builder()
					.hookSpecificOutput(HookSpecificOutput.preToolUseDeny(DENY_REASON))
					.build());
	}

	@Nested
	@DisplayName("ClaudeSyncClient")
	class Sync {

		@Test
		@DisplayName("registers hooks with a control_request initialize and answers a PreToolUse deny in the hook JSON output format")
		void preToolUseDeny() throws Exception {
			try (ClaudeSyncClient client = ClaudeClient.sync()
				.workingDirectory(tempDir)
				.claudePath(stubCli)
				.hookRegistry(hooks)
				.timeout(ARRIVAL_TIMEOUT)
				.build()) {
				client.connect("run git switch -c probe");

				assertInitializeRegistersHook(awaitInitialize());
				assertDenyResponse(awaitHookResponse());
			}
		}

	}

	@Nested
	@DisplayName("ClaudeAsyncClient")
	class Async {

		@Test
		@DisplayName("registers hooks with a control_request initialize and answers a PreToolUse deny in the hook JSON output format")
		void preToolUseDeny() throws Exception {
			ClaudeAsyncClient client = asyncClient(stubCli);
			try {
				Disposable turn = client.connect("run git switch -c probe").messages().subscribe();
				try {
					assertInitializeRegistersHook(awaitInitialize());
					assertDenyResponse(awaitHookResponse());
				}
				finally {
					turn.dispose();
				}
			}
			finally {
				client.close().block(ARRIVAL_TIMEOUT);
			}
		}

		@Test
		@DisplayName("a refused initialize fails the connect")
		void initializeRefused() throws Exception {
			ClaudeAsyncClient client = asyncClient(writeStubCli(INITIALIZE_REFUSED));
			try {
				assertThatThrownBy(() -> client.connect().block(ARRIVAL_TIMEOUT)).isInstanceOf(TransportException.class)
					.rootCause()
					.hasMessageContaining("hooks rejected");
			}
			finally {
				client.close().block(ARRIVAL_TIMEOUT);
			}
		}

		@Test
		@DisplayName("setPermissionMode completes once the CLI accepts it")
		void setPermissionModeAccepted() throws Exception {
			DefaultClaudeAsyncClient client = (DefaultClaudeAsyncClient) asyncClient(stubCli);
			try {
				client.connect().block(ARRIVAL_TIMEOUT);

				client.setPermissionMode("plan").block(ARRIVAL_TIMEOUT);

				assertThat(client.getCurrentPermissionMode()).isEqualTo("plan");
			}
			finally {
				client.close().block(ARRIVAL_TIMEOUT);
			}
		}

		@Test
		@DisplayName("a refused setModel fails and keeps the current model")
		void setModelRefused() throws Exception {
			DefaultClaudeAsyncClient client = (DefaultClaudeAsyncClient) asyncClient(stubCli);
			try {
				client.connect().block(ARRIVAL_TIMEOUT);
				String before = client.getCurrentModel();

				assertThatThrownBy(() -> client.setModel("no-such-model").block(ARRIVAL_TIMEOUT))
					.isInstanceOf(ClaudeSDKException.class)
					.hasMessageContaining("request rejected");
				assertThat(client.getCurrentModel()).isEqualTo(before);
			}
			finally {
				client.close().block(ARRIVAL_TIMEOUT);
			}
		}

		@Test
		@DisplayName("a refused interrupt fails")
		void interruptRefused() throws Exception {
			ClaudeAsyncClient client = asyncClient(stubCli);
			try {
				client.connect().block(ARRIVAL_TIMEOUT);

				assertThatThrownBy(() -> client.interrupt().block(ARRIVAL_TIMEOUT))
					.isInstanceOf(ClaudeSDKException.class)
					.hasMessageContaining("request rejected");
			}
			finally {
				client.close().block(ARRIVAL_TIMEOUT);
			}
		}

	}

	private ClaudeAsyncClient asyncClient(String cli) {
		return ClaudeClient.async()
			.workingDirectory(tempDir)
			.claudePath(cli)
			.hookRegistry(hooks)
			.timeout(ARRIVAL_TIMEOUT)
			.build();
	}

	private void assertDenyResponse(JsonNode response) throws IOException {
		assertThat(response.at("/response/subtype").asText()).isEqualTo("success");
		assertThat(response.at("/response/response")).isEqualTo(MAPPER.readTree("""
				{"hookSpecificOutput": {
				  "hookEventName": "PreToolUse",
				  "permissionDecision": "deny",
				  "permissionDecisionReason": "%s"}}
				""".formatted(DENY_REASON)));
	}

	private void assertInitializeRegistersHook(JsonNode initialize) throws IOException {
		assertThat(initialize.path("request_id").asText()).isNotBlank();
		assertThat(initialize.at("/request/hooks")).isEqualTo(MAPPER.readTree("""
				{"PreToolUse": [{"matcher": "Bash", "hookCallbackIds": ["hook_0"], "timeout": 60}]}
				"""));
	}

	/**
	 * The initialize request, in the only envelope the CLI reads: {@code control_request}
	 * with the payload nested under {@code request}.
	 */
	private JsonNode awaitInitialize() throws Exception {
		return awaitLine(node -> "control_request".equals(node.path("type").asText())
				&& "initialize".equals(node.at("/request/subtype").asText()));
	}

	private JsonNode awaitHookResponse() throws Exception {
		return awaitLine(node -> "control_response".equals(node.path("type").asText())
				&& HOOK_REQUEST_ID.equals(node.at("/response/request_id").asText()));
	}

	private JsonNode awaitLine(Predicate<JsonNode> match) throws Exception {
		long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			Optional<JsonNode> found = recordedLines().stream().filter(match).findFirst();
			if (found.isPresent()) {
				return found.get();
			}
			Thread.sleep(25);
		}
		throw new AssertionError("No matching line reached the CLI within " + ARRIVAL_TIMEOUT + "; recorded: "
				+ Files.readString(recording, StandardCharsets.UTF_8));
	}

	private List<JsonNode> recordedLines() throws IOException {
		List<JsonNode> lines = new ArrayList<>();
		for (String line : Files.readAllLines(recording, StandardCharsets.UTF_8)) {
			if (!line.isBlank()) {
				lines.add(MAPPER.readTree(line));
			}
		}
		return lines;
	}

	/**
	 * Writes a stand-in for the Claude CLI: it records each line the SDK sends, answers
	 * {@code initialize} with {@code initializeReply} (a printf format whose {@code %s}
	 * is the request ID), accepts {@code set_permission_mode}, refuses {@code set_model}
	 * and {@code interrupt}, and asks for the {@code hook_0} PreToolUse callback once a
	 * user message arrives. It contacts nothing.
	 */
	private String writeStubCli(String initializeReply) throws IOException {
		Path stub = tempDir.resolve("claude-stub.sh");
		String hookCallback = """
				{"type":"control_request","request_id":"%s","request":{"subtype":"hook_callback",\
				"callback_id":"hook_0","tool_use_id":"tool_1","input":{"hook_event_name":"PreToolUse",\
				"session_id":"sess_1","transcript_path":"/tmp/t.jsonl","cwd":"/tmp","tool_name":"Bash",\
				"tool_input":{"command":"git switch -c probe"}}}}""".formatted(HOOK_REQUEST_ID);
		String script = """
				#!/bin/sh
				# Deterministic stand-in for the Claude CLI used by HookCallbackWireTest.
				reply() {
				    id=$(printf '%%s\\n' "$line" | sed -n 's/.*"request_id":"\\([^"]*\\)".*/\\1/p')
				    printf "$1\\n" "$id"
				}
				while IFS= read -r line; do
				    printf '%%s\\n' "$line" >> '%s'
				    case "$line" in
				        *'"subtype":"initialize"'*) reply '%s' ;;
				        *'"subtype":"set_permission_mode"'*) reply '%s' ;;
				        *'"subtype":"set_model"'*|*'"subtype":"interrupt"'*) reply '%s' ;;
				        *'"type":"user"'*)
				            printf '%%s\\n' '%s'
				            ;;
				    esac
				done
				""".formatted(recording.toAbsolutePath(), initializeReply, CONTROL_SUCCESS, CONTROL_REFUSED,
				hookCallback);
		Files.writeString(stub, script, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwxr-xr-x"));
		return stub.toAbsolutePath().toString();
	}

}
