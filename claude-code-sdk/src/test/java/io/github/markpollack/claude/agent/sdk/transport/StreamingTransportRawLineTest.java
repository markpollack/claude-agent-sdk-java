/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raw line listener sees every line the CLI wrote to standard output as the reader delivers
 * it, line terminator removed and otherwise unchanged: the blank line, the line with surrounding
 * spaces, the message type the SDK does not know, the line that is not valid JSON and the partial
 * last line without a line break. The stub CLI is a shell
 * script, so no agent CLI is invoked.
 */
class StreamingTransportRawLineTest {

	private Path stubCli(Path dir) throws IOException {
		Path script = dir.resolve("stub-cli.sh");
		Files.writeString(script, """
				#!/bin/sh
				printf '%s\\n' '{"type":"system","subtype":"init","session_id":"s1"}'
				printf '\\n'
				printf '%s\\n' '   {"type":"future_event","x":1}   '
				printf '%s\\n' '{"type":"assistant","message":'
				printf '%s\\n' '{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1,"rateLimitType":"five_hour"},"uuid":"u","session_id":"s1"}'
				printf '%s' '{"type":"result","partial'
				""");
		script.toFile().setExecutable(true);
		return script;
	}

	@Test
	void everyStdoutLineReachesTheListenerExactlyAsRead(@TempDir Path dir) throws Exception {
		Path cli = stubCli(dir);
		List<String> raw = new CopyOnWriteArrayList<>();
		List<ParsedMessage> parsed = new CopyOnWriteArrayList<>();
		StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(30), cli.toString());
		try {
			transport.startSession("go", CLIOptions.builder().rawLineListener(raw::add).build(), parsed::add, null);
			for (int i = 0; i < 100 && raw.size() < 6; i++) {
				Thread.sleep(100);
			}
		}
		finally {
			transport.close();
		}

		assertThat(raw).containsExactly("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\"}", "",
				"   {\"type\":\"future_event\",\"x\":1}   ", "{\"type\":\"assistant\",\"message\":",
				"{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\",\"resetsAt\":1,\"rateLimitType\":\"five_hour\"},\"uuid\":\"u\",\"session_id\":\"s1\"}",
				"{\"type\":\"result\",\"partial");
		// the parsed side is unchanged: the unknown, malformed and partial lines never became messages
		assertThat(parsed).anySatisfy(p -> assertThat(p.isRegularMessage()).isTrue());
		assertThat(parsed).anySatisfy(p -> assertThat(p.isRateLimitEvent()).isTrue());
		// and the original line of a message that did parse is available whatever its kind
		assertThat(parsed).allSatisfy(p -> {
			if (!(p instanceof ParsedMessage.EndOfStream)) {
				assertThat(p.rawJson()).isNotNull();
			}
		});
	}

	@Test
	void aListenerThatThrowsDoesNotStopTheStream(@TempDir Path dir) throws Exception {
		Path cli = stubCli(dir);
		List<ParsedMessage> parsed = new CopyOnWriteArrayList<>();
		StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(30), cli.toString());
		try {
			transport.startSession("go", CLIOptions.builder().rawLineListener(l -> {
				throw new IllegalStateException("listener bug");
			}).build(), parsed::add, null);
			for (int i = 0; i < 100 && parsed.size() < 2; i++) {
				Thread.sleep(100);
			}
		}
		finally {
			transport.close();
		}
		assertThat(parsed).anySatisfy(p -> assertThat(p.isRateLimitEvent()).isTrue());
	}

}
