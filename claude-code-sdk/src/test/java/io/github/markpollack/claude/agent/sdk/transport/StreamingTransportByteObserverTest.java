/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.control.ControlResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real process pipes with a shell stand-in; no provider access or recorded user data. */
@DisabledOnOs(OS.WINDOWS)
class StreamingTransportByteObserverTest {

	@TempDir
	Path dir;

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"byte-test\"}";

	@Test
	void originalBytesSurviveParsingAndControlRoundTrip() throws Exception {
		byte[] tail = ("  {\"type\":\"future_event\",\"text\":\"雪😀\"}  \r\n\n" + "x".repeat(18000) + "\r\n")
			.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		stdout.write((INIT + "\r\n").getBytes(StandardCharsets.UTF_8));
		stdout.write(("{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\","
				+ "\"tool_name\":\"Read\",\"input\":{}}}\n")
			.getBytes(StandardCharsets.UTF_8));
		byte[] head = stdout.toByteArray();
		Files.write(dir.resolve("head"), head);
		Files.write(dir.resolve("tail"), tail);
		byte[] partial = new byte[] { (byte) 0xff, (byte) 0xc3, 0x28, 'z' };
		Files.write(dir.resolve("partial"), partial);
		byte[] stderr = ("diagnostic\r\n" + "é".repeat(12000) + "\nlast").getBytes(StandardCharsets.UTF_8);
		stderr[stderr.length - 2] = (byte) 0xff;
		Files.write(dir.resolve("errors"), stderr);
		Recording recording = new Recording();
		CountDownLatch control = new CountDownLatch(1);
		Path cli = stub("""
				IFS= read -r prompt
				printf '%s\n' "$prompt" > received
				cat head
				IFS= read -r response
				printf '%s\n' "$response" >> received
				cat tail
				cat partial
				cat errors >&2
				""");
		try (StreamingTransport transport = transport(cli)) {
			transport.startSession("hello 雪😀", CLIOptions.builder().transportByteObserver(recording).build(), p -> {
			}, request -> {
				control.countDown();
				return ControlResponse.success(request.requestId(), Map.of("behavior", "allow"));
			});
			assertThat(transport.waitForCompletion(TIMEOUT)).isTrue();
			assertThat(control.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(transport.getSessionError()).isNull();
		}
		stdout.write(tail);
		stdout.write(partial);
		assertThat(recording.bytes(TransportByteObserver.Stream.STDOUT)).isEqualTo(stdout.toByteArray());
		assertThat(recording.bytes(TransportByteObserver.Stream.STDERR)).isEqualTo(stderr);
		byte[] received = Files.readAllBytes(dir.resolve("received"));
		assertThat(recording.bytes(TransportByteObserver.Stream.STDIN)).isEqualTo(received);
		assertThat(new String(received, StandardCharsets.UTF_8)).contains("hello 雪😀", "r1", "control_response");
		assertThat(recording.chunks.get(TransportByteObserver.Stream.STDOUT)).hasSizeGreaterThan(1);
	}

	@Test
	void mutationOfObserverOwnedArrayCannotChangeParserInput() throws Exception {
		CountDownLatch parsed = new CountDownLatch(1);
		Path cli = stub("printf '%s\\n' '" + INIT + "'\n");
		try (StreamingTransport transport = transport(cli)) {
			transport.startSession(null,
					CLIOptions.builder().transportByteObserver((stream, bytes) -> Arrays.fill(bytes, (byte) 0)).build(),
					p -> {
						if (!(p instanceof ParsedMessage.EndOfStream))
							parsed.countDown();
					}, null);
			assertThat(transport.waitForCompletion(TIMEOUT)).isTrue();
			assertThat(parsed.await(1, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void stderrDrainsAfterStdoutEofIncludingFinalUnterminatedBytes() throws Exception {
		CountDownLatch stdoutEnded = new CountDownLatch(1);
		Recording recording = new Recording();
		byte[] tail = ("t".repeat(32000) + "雪-final").getBytes(StandardCharsets.UTF_8);
		Files.write(dir.resolve("tail"), tail);
		Path cli = stub("printf 'ready\\n' >&2\nexec 1>&-\ncat tail >&2\n");
		CLIOptions options = CLIOptions.builder().transportByteObserver(recording).stderrHandler(line -> {
			try {
				if (!stdoutEnded.await(5, TimeUnit.SECONDS))
					throw new IllegalStateException("stdout never ended");
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}).build();
		try (StreamingTransport transport = transport(cli)) {
			transport.startSession(null, options, p -> {
				if (p instanceof ParsedMessage.EndOfStream)
					stdoutEnded.countDown();
			}, null);
			assertThat(transport.waitForCompletion(TIMEOUT)).isTrue();
			assertThat(transport.getSessionError()).isNull();
		}
		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write("ready\n".getBytes(StandardCharsets.UTF_8));
		expected.write(tail);
		assertThat(recording.bytes(TransportByteObserver.Stream.STDERR)).isEqualTo(expected.toByteArray());
	}

	@ParameterizedTest
	@EnumSource(TransportByteObserver.Stream.class)
	void everyStreamRetainsObserverFailure(TransportByteObserver.Stream failedStream) throws Exception {
		IOException failure = new IOException("recording unavailable");
		Path cli = stub("printf 'diagnostic\\n' >&2\nprintf '%s\\n' '" + INIT + "'\nIFS= read -r prompt\n");
		StreamingTransport transport = transport(cli);
		try {
			transport.startSession("hello", CLIOptions.builder().transportByteObserver((stream, bytes) -> {
				if (stream == failedStream)
					throw failure;
			}).build(), p -> {
			}, null);
			await(() -> transport.getSessionError() != null);
			assertThat(transport.getSessionError()).hasCause(failure).hasMessageContaining(failedStream.name());
		}
		finally {
			transport.close();
		}
		assertThat(transport.getSessionError()).hasCause(failure);
	}

	@Test
	void failureDuringCloseRemainsVisibleAndCloseWaitsForCallback() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Path cli = stub("printf 'error\\n' >&2\nexec sleep 60\n");
		StreamingTransport transport = transport(cli);
		try (var executor = Executors.newSingleThreadExecutor()) {
			transport.startSession(null, CLIOptions.builder().transportByteObserver((stream, bytes) -> {
				if (stream == TransportByteObserver.Stream.STDERR) {
					entered.countDown();
					try {
						release.await(5, TimeUnit.SECONDS);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					throw new IOException("late recording failure");
				}
			}).build(), p -> {
			}, null);
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			var close = executor.submit(transport::close);
			await(() -> transport.getState() == StreamingTransport.STATE_CLOSING);
			assertThat(close.isDone()).isFalse();
			release.countDown();
			close.get(10, TimeUnit.SECONDS);
			assertThat(transport.getSessionError()).hasRootCauseMessage("late recording failure");
		}
		finally {
			release.countDown();
			transport.close();
		}
	}

	@Test
	void syncFacadeRecorderLatchRejectsCaptureEvenWhenModelResultArrives() throws Exception {
		AtomicReference<IOException> captureFailure = new AtomicReference<>();
		CountDownLatch failed = new CountDownLatch(1);
		Path cli = stub(
				"""
						IFS= read -r prompt
						printf 'diagnostic\n' >&2
						printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"result":"ok","session_id":"byte-test","total_cost_usd":0}'
						""");
		CLIOptions options = CLIOptions.builder().transportByteObserver((stream, bytes) -> {
			if (stream == TransportByteObserver.Stream.STDERR) {
				IOException failure = new IOException("disk full");
				captureFailure.compareAndSet(null, failure);
				failed.countDown();
				throw failure;
			}
		}).build();
		List<ParsedMessage> messages = new ArrayList<>();
		try (ClaudeSyncClient client = ClaudeClient.sync(options)
			.workingDirectory(dir)
			.claudePath(cli.toString())
			.timeout(TIMEOUT)
			.build()) {
			client.connect("hello");
			client.receiveResponse().forEachRemaining(messages::add);
			assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(messages).anySatisfy(p -> assertThat(p.rawJson()).contains("\"result\":\"ok\""));
		assertThat(captureFailure.get()).hasMessage("disk full");
	}

	@Test
	void absentObserverKeepsOrdinaryProtocolBehavior() throws Exception {
		assertThat(CLIOptions.defaultOptions().transportByteObserver()).isNull();
		CountDownLatch parsed = new CountDownLatch(1);
		Path cli = stub("printf '%s\\n' '" + INIT + "'\n");
		try (StreamingTransport transport = transport(cli)) {
			transport.startSession(null, CLIOptions.builder().build(), p -> {
				if (!(p instanceof ParsedMessage.EndOfStream))
					parsed.countDown();
			}, null);
			assertThat(parsed.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(transport.waitForCompletion(TIMEOUT)).isTrue();
		}
	}

	private Path stub(String body) throws IOException {
		Path script = dir.resolve("stub.sh");
		Files.writeString(script, "#!/bin/sh\n" + body);
		assertThat(script.toFile().setExecutable(true)).isTrue();
		return script;
	}

	private StreamingTransport transport(Path cli) {
		return new StreamingTransport(dir, TIMEOUT, cli.toString());
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
			Thread.sleep(10);
		assertThat(condition.getAsBoolean()).isTrue();
	}

	private static final class Recording implements TransportByteObserver {

		private final Map<Stream, List<byte[]>> chunks = new EnumMap<>(Stream.class);

		private Recording() {
			for (Stream stream : Stream.values())
				chunks.put(stream, new ArrayList<>());
		}

		@Override
		public synchronized void onBytes(Stream stream, byte[] bytes) {
			chunks.get(stream).add(bytes);
		}

		synchronized byte[] bytes(Stream stream) throws IOException {
			ByteArrayOutputStream result = new ByteArrayOutputStream();
			for (byte[] chunk : chunks.get(stream))
				result.write(chunk);
			return result.toByteArray();
		}

	}

}
