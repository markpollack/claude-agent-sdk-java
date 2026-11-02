/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.ClaudeAsyncClient;
import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
import io.github.markpollack.claude.agent.sdk.mcp.McpServerConfig;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Real local process stand-in, with faults confined to config materialization. */
@DisabledOnOs(OS.WINDOWS)
class StreamingTransportMcpSetupTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String PROMPT = "local-required-config-check";

	private static final String SCHEDULER_HOOK = StreamingTransportMcpSetupTest.class.getName();

	@TempDir
	Path dir;

	enum Entry {
		TRANSPORT, SYNC, ASYNC
	}

	enum Fault {
		SERIALIZE, CREATE, WRITE, WRITE_CLEANUP_IO, WRITE_CLEANUP_SECURITY
	}

	static Stream<Arguments> failures() {
		return Stream.of(Entry.values())
			.flatMap(entry -> Stream.of(Fault.values()).map(fault -> Arguments.of(entry, fault)));
	}

	static Stream<Arguments> healthy() {
		return Stream.of(Entry.values())
			.flatMap(entry -> Stream.of(false, true).map(configured -> Arguments.of(entry, configured)));
	}

	@ParameterizedTest
	@MethodSource("failures")
	void requiredSetupFailurePreventsProcessAndPrompt(Entry entry, Fault fault) throws Exception {
		Path child = stub();
		Path unrelated = Files.writeString(dir.resolve("unrelated.json"), "keep");
		AtomicInteger observedBytes = new AtomicInteger();
		CLIOptions options = options(true, observedBytes);
		IOException setup = fault == Fault.SERIALIZE ? new JsonProcessingException("serialization failed") {
		} : new IOException("required setup failed");
		Exception cleanup = fault == Fault.WRITE_CLEANUP_SECURITY ? new SecurityException("cleanup denied")
				: new IOException("cleanup failed");
		AtomicReference<Path> partial = new AtomicReference<>();
		AtomicInteger deletions = new AtomicInteger();
		AtomicReference<Throwable> retained = new AtomicReference<>();
		AtomicBoolean faultInstalled = new AtomicBoolean();
		Runnable connect = () -> connectExpectingFailure(entry, child, options, retained);
		if (entry == Entry.ASYNC) {
			// Mockito faults are thread-local. Install them around the actual async
			// connection task, without replacing or mocking the transport/facade.
			AtomicBoolean first = new AtomicBoolean(true);
			AtomicReference<Runnable> scheduled = new AtomicReference<>();
			Runnable faultTask = faultTask(fault, setup, cleanup, partial, deletions, faultInstalled, scheduled);
			Schedulers.onScheduleHook(SCHEDULER_HOOK, task -> {
				if (first.compareAndSet(true, false)) {
					scheduled.set(task);
					return faultTask;
				}
				return task;
			});
			try {
				connect.run();
			}
			finally {
				Schedulers.resetOnScheduleHook(SCHEDULER_HOOK);
			}
		}
		else {
			faultTask(fault, setup, cleanup, partial, deletions, faultInstalled, new AtomicReference<>(connect)).run();
		}
		assertThat(faultInstalled).isTrue();
		assertThat(retained.get()).isInstanceOf(TransportException.class);
		assertThat(causes(retained.get())).contains(setup);
		assertThat(retained.get().getMessage()).doesNotContain("private-header-value");
		assertThat(Files.exists(dir.resolve("started"))).isFalse();
		assertThat(Files.exists(dir.resolve("received"))).isFalse();
		assertThat(observedBytes).hasValue(0);
		assertThat(Files.readString(unrelated)).isEqualTo("keep");
		if (partial.get() != null) {
			assertThat(deletions).hasValue(1);
			if (fault == Fault.WRITE) {
				assertThat(Files.exists(partial.get())).isFalse();
				assertThat(setup.getSuppressed()).isEmpty();
			}
			else {
				assertThat(Files.readString(partial.get())).isEqualTo("{");
				assertThat(setup.getSuppressed()).containsExactly(cleanup);
			}
		}
	}

	private Runnable faultTask(Fault fault, IOException setup, Exception cleanup, AtomicReference<Path> partial,
			AtomicInteger deletions, AtomicBoolean installed, AtomicReference<Runnable> body) {
		return () -> {
			installed.set(true);
			try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS);
					MockedConstruction<ObjectMapper> mappers = fault == Fault.SERIALIZE
							? mockConstruction(ObjectMapper.class, (mapper, context) -> when(mapper.writeValueAsString(any()))
								.thenThrow(setup))
							: null) {
				if (fault == Fault.CREATE) {
					files.when(() -> Files.createTempFile("claude-mcp-", ".json")).thenThrow(setup);
				}
				if (fault == Fault.WRITE || fault == Fault.WRITE_CLEANUP_IO || fault == Fault.WRITE_CLEANUP_SECURITY) {
					files.when(() -> Files.createTempFile("claude-mcp-", ".json")).thenAnswer(call -> {
						Path path = Files.createTempFile(dir, "owned-partial-", ".json");
						partial.set(path);
						return path;
					});
					files.when(() -> Files.writeString(any(Path.class), any(CharSequence.class))).thenAnswer(call -> {
						Files.write(call.getArgument(0), new byte[] { '{' });
						throw setup;
					});
					files.when(() -> Files.deleteIfExists(any(Path.class))).thenAnswer(call -> {
						assertThat((Path) call.getArgument(0)).isEqualTo(partial.get());
						deletions.incrementAndGet();
						if (fault != Fault.WRITE) {
							throw cleanup;
						}
						return call.callRealMethod();
					});
				}
				body.get().run();
			}
		};
	}

	private void connectExpectingFailure(Entry entry, Path child, CLIOptions options, AtomicReference<Throwable> error) {
		if (entry == Entry.TRANSPORT) {
			try (StreamingTransport transport = new StreamingTransport(dir, TIMEOUT, child.toString())) {
				error.set(catchThrowable(() -> transport.startSession(PROMPT, options, p -> {
				}, null)));
				assertThat(transport.getSessionError()).isSameAs(error.get());
				assertThat(transport.isRunning()).isFalse();
				assertThat(transport.getState()).isEqualTo(StreamingTransport.STATE_DISCONNECTED);
			}
		}
		else if (entry == Entry.SYNC) {
			try (ClaudeSyncClient client = ClaudeClient.sync(options).workingDirectory(dir).claudePath(child.toString())
				.timeout(TIMEOUT).build()) {
				error.set(catchThrowable(() -> client.connect(PROMPT)));
				assertThat(client.isConnected()).isFalse();
				assertThat(catchThrowable(() -> client.query("must-not-send"))).isInstanceOf(IllegalStateException.class);
			}
		}
		else {
			ClaudeAsyncClient client = ClaudeClient.async(options).workingDirectory(dir).claudePath(child.toString())
				.timeout(TIMEOUT).build();
			try {
				error.set(catchThrowable(() -> client.connect(PROMPT).messages().collectList().block(TIMEOUT)));
				assertThat(client.isConnected()).isFalse();
				assertThat(catchThrowable(() -> client.query("must-not-send").messages().collectList().block(TIMEOUT)))
					.isInstanceOf(IllegalStateException.class);
			}
			finally {
				client.close().block(TIMEOUT);
			}
		}
	}

	@ParameterizedTest
	@MethodSource("healthy")
	void healthyOrEmptyConfigurationLaunchesWithExactlyRequestedBytes(Entry entry, boolean configured) throws Exception {
		Path child = stub();
		CLIOptions options = options(configured, new AtomicInteger());
		if (entry == Entry.TRANSPORT) {
			try (StreamingTransport transport = new StreamingTransport(dir, TIMEOUT, child.toString())) {
				transport.startSession(PROMPT, options, p -> {
				}, null);
				assertThat(transport.waitForCompletion(TIMEOUT)).isTrue();
				assertThat(transport.getSessionError()).isNull();
			}
		}
		else if (entry == Entry.SYNC) {
			try (ClaudeSyncClient client = ClaudeClient.sync(options).workingDirectory(dir).claudePath(child.toString())
				.timeout(TIMEOUT).build()) {
				client.connect(PROMPT);
				client.receiveResponse().forEachRemaining(p -> {
				});
			}
		}
		else {
			ClaudeAsyncClient client = ClaudeClient.async(options).workingDirectory(dir).claudePath(child.toString())
				.timeout(TIMEOUT).build();
			try {
				client.connect(PROMPT).messages().collectList().block(TIMEOUT);
			}
			finally {
				client.close().block(TIMEOUT);
			}
		}
		assertThat(Files.readString(dir.resolve("started"))).isEqualTo("started\n");
		assertThat(Files.readAllLines(dir.resolve("received"))).hasSize(1);
		assertThat(Files.readString(dir.resolve("received"))).contains(PROMPT);
		List<String> args = Files.readAllLines(dir.resolve("args"));
		if (configured) {
			assertThat(args.stream().filter("--mcp-config"::equals).count()).isEqualTo(1);
			var config = new ObjectMapper().readTree(Files.readString(dir.resolve("config-copy.json")));
			assertThat(config.path("mcpServers").size()).isEqualTo(1);
			assertThat(config.at("/mcpServers/required/url").asText()).isEqualTo("http://127.0.0.1:1/not-contacted");
			assertThat(config.at("/mcpServers/required/headers/Authorization").asText()).isEqualTo("private-header-value");
			assertThat(Files.exists(Path.of(args.get(args.indexOf("--mcp-config") + 1)))).isFalse();
		}
		else {
			assertThat(args).doesNotContain("--mcp-config");
			assertThat(Files.exists(dir.resolve("config-copy.json"))).isFalse();
		}
	}

	private CLIOptions options(boolean configured, AtomicInteger bytes) {
		return CLIOptions.builder()
			.mcpServers(configured ? Map.of("required", new McpServerConfig.McpHttpServerConfig(
					"http://127.0.0.1:1/not-contacted", Map.of("Authorization", "private-header-value"))) : Map.of())
			.transportByteObserver((stream, value) -> bytes.addAndGet(value.length))
			.build();
	}

	private Path stub() throws IOException {
		Path child = dir.resolve("local-cli-stand-in.sh");
		Files.writeString(child, """
				#!/bin/sh
				printf 'started\n' > started
				printf '%s\n' "$@" > args
				while [ "$#" -gt 0 ]; do
				  if [ "$1" = '--mcp-config' ]; then shift; cat "$1" > config-copy.json; fi
				  shift
				done
				IFS= read -r prompt
				printf '%s\n' "$prompt" > received
				printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"result":"ok","session_id":"local-mcp-test","total_cost_usd":0}'
				""");
		assertThat(child.toFile().setExecutable(true)).isTrue();
		return child;
	}

	private List<Throwable> causes(Throwable error) {
		List<Throwable> result = new ArrayList<>();
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			result.add(cause);
		}
		return result;
	}

}
