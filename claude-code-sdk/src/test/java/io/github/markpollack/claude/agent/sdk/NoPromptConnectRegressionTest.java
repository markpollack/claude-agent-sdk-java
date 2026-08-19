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

package io.github.markpollack.claude.agent.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the no-argument {@code connect()} contract.
 *
 * <p>
 * Both clients used to convert a missing initial prompt into the literal string
 * {@code "Hello"} and send it as a user message. That contradicts the documented contract
 * — "connects without an initial prompt" — and bills a model turn the caller never asked
 * for. These tests pin the corrected behaviour: {@code connect()} starts and initialises
 * the session and writes nothing to the CLI, while {@code connect(String)} sends exactly
 * the caller's message and nothing else.
 * </p>
 *
 * <p>
 * The CLI is a generated shell stub that records every line the SDK writes to its stdin.
 * Nothing here starts a real Claude CLI, needs credentials, or bills model usage.
 * </p>
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("connect() must not send a synthetic user message")
class NoPromptConnectRegressionTest {

	/**
	 * Time allowed for an expected message to travel the outbound scheduler and reach the
	 * stub's recording.
	 */
	private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Quiet period observed after the expected traffic has arrived, so that an extra
	 * unsolicited message would have time to show up and fail the assertion.
	 */
	private static final Duration SETTLE = Duration.ofMillis(750);

	private static final String SENTINEL = "sentinel-first-user-message";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	Path tempDir;

	private Path recording;

	private String stubCli;

	@BeforeEach
	void setUp() throws IOException {
		this.recording = tempDir.resolve("cli-stdin.jsonl");
		Files.createFile(recording);
		this.stubCli = writeStubCli();
	}

	@Nested
	@DisplayName("ClaudeSyncClient")
	class Sync {

		@Test
		@DisplayName("connect() writes nothing at all to the CLI")
		void noArgConnectWritesNothing() throws Exception {
			try (ClaudeSyncClient client = newSyncClient()) {
				client.connect();
				quietPeriod();

				assertThat(recordedLines()).describedAs("stdin traffic after a no-argument connect()").isEmpty();
			}
		}

		@Test
		@DisplayName("connect() leaves the caller's first query as the first user message")
		void noArgConnectSendsNoUserMessage() throws Exception {
			try (ClaudeSyncClient client = newSyncClient()) {
				client.connect();
				client.query(SENTINEL);

				List<String> messages = awaitUserMessages(1);

				// Ordering, not timing, is the proof: both sends are serialised through
				// one outbound sink, so a synthetic prompt would necessarily be recorded
				// ahead of the sentinel.
				assertThat(messages).first().describedAs("first user message the CLI ever sees").isEqualTo(SENTINEL);
				quietPeriod();
				assertThat(userMessages()).containsExactly(SENTINEL);
			}
		}

		@Test
		@DisplayName("connect(prompt) sends exactly the caller's message, once")
		void explicitConnectSendsOnlyTheCallersPrompt() throws Exception {
			String prompt = "Summarise the build failure";
			try (ClaudeSyncClient client = newSyncClient()) {
				client.connect(prompt);

				assertThat(awaitUserMessages(1)).containsExactly(prompt);
				quietPeriod();
				assertThat(userMessages()).containsExactly(prompt);
			}
		}

		@Test
		@DisplayName("connect() then query() still delivers subsequent turns in order")
		void subsequentQueriesStillWork() throws Exception {
			try (ClaudeSyncClient client = newSyncClient()) {
				client.connect();
				client.query("first");
				client.query("second");

				assertThat(awaitUserMessages(2)).containsExactly("first", "second");
			}
		}

	}

	@Nested
	@DisplayName("ClaudeAsyncClient")
	class Async {

		@Test
		@DisplayName("connect() writes nothing at all to the CLI")
		void noArgConnectWritesNothing() throws Exception {
			ClaudeAsyncClient client = newAsyncClient();
			try {
				client.connect().block(ARRIVAL_TIMEOUT);
				quietPeriod();

				assertThat(recordedLines()).describedAs("stdin traffic after a no-argument connect()").isEmpty();
			}
			finally {
				closeQuietly(client);
			}
		}

		@Test
		@DisplayName("connect() leaves the caller's first query as the first user message")
		void noArgConnectSendsNoUserMessage() throws Exception {
			ClaudeAsyncClient client = newAsyncClient();
			Disposable turn = null;
			try {
				client.connect().block(ARRIVAL_TIMEOUT);
				turn = client.query(SENTINEL).messages().subscribe();

				List<String> messages = awaitUserMessages(1);

				assertThat(messages).first().describedAs("first user message the CLI ever sees").isEqualTo(SENTINEL);
				quietPeriod();
				assertThat(userMessages()).containsExactly(SENTINEL);
			}
			finally {
				dispose(turn);
				closeQuietly(client);
			}
		}

		@Test
		@DisplayName("connect(prompt) sends exactly the caller's message, once")
		void explicitConnectSendsOnlyTheCallersPrompt() throws Exception {
			String prompt = "Summarise the build failure";
			ClaudeAsyncClient client = newAsyncClient();
			Disposable turn = null;
			try {
				turn = client.connect(prompt).messages().subscribe();

				assertThat(awaitUserMessages(1)).containsExactly(prompt);
				quietPeriod();
				assertThat(userMessages()).containsExactly(prompt);
			}
			finally {
				dispose(turn);
				closeQuietly(client);
			}
		}

	}

	// ---------------------------------------------------------------- helpers

	private ClaudeSyncClient newSyncClient() {
		return ClaudeClient.sync().workingDirectory(tempDir).claudePath(stubCli).timeout(ARRIVAL_TIMEOUT).build();
	}

	private ClaudeAsyncClient newAsyncClient() {
		return ClaudeClient.async().workingDirectory(tempDir).claudePath(stubCli).timeout(ARRIVAL_TIMEOUT).build();
	}

	/**
	 * Writes a stand-in for the Claude CLI. It appends each line the SDK sends to its
	 * stdin to the recording file and produces no output, so no model is ever contacted.
	 * The read loop appends line by line rather than piping, so a recorded message is
	 * visible to the test as soon as the SDK flushes it.
	 */
	private String writeStubCli() throws IOException {
		Path stub = tempDir.resolve("claude-stub.sh");
		String script = """
				#!/bin/sh
				# Deterministic stand-in for the Claude CLI used by NoPromptConnectRegressionTest.
				# Records every line written to stdin; contacts nothing.
				while IFS= read -r line; do
				    printf '%%s\\n' "$line" >> '%s'
				done
				""".formatted(recording.toAbsolutePath());
		Files.writeString(stub, script, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwxr-xr-x"));
		return stub.toAbsolutePath().toString();
	}

	private List<String> recordedLines() throws IOException {
		List<String> lines = new ArrayList<>();
		for (String line : Files.readAllLines(recording, StandardCharsets.UTF_8)) {
			if (!line.isBlank()) {
				lines.add(line);
			}
		}
		return lines;
	}

	/**
	 * The content of every {@code type: user} message recorded so far, in wire order.
	 */
	private List<String> userMessages() throws IOException {
		List<String> contents = new ArrayList<>();
		for (String line : recordedLines()) {
			JsonNode node = MAPPER.readTree(line);
			if ("user".equals(node.path("type").asText())) {
				contents.add(node.path("message").path("content").asText());
			}
		}
		return contents;
	}

	private List<String> awaitUserMessages(int expected) throws Exception {
		long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
		List<String> messages = userMessages();
		while (messages.size() < expected && System.nanoTime() < deadline) {
			Thread.sleep(25);
			messages = userMessages();
		}
		assertThat(messages).describedAs("user messages recorded within %s", ARRIVAL_TIMEOUT)
			.hasSizeGreaterThanOrEqualTo(expected);
		return messages;
	}

	private static void dispose(Disposable subscription) {
		if (subscription != null) {
			subscription.dispose();
		}
	}

	/**
	 * {@link ClaudeAsyncClient} is not {@code AutoCloseable} — its {@code close()}
	 * returns a {@code Mono} — so shutdown is driven explicitly.
	 */
	private static void closeQuietly(ClaudeAsyncClient client) {
		client.close().block(ARRIVAL_TIMEOUT);
	}

	private void quietPeriod() throws InterruptedException {
		Thread.sleep(SETTLE.toMillis());
	}

}
