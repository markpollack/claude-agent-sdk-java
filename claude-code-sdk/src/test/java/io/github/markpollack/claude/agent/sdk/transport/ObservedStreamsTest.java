/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedStreamsTest {

	@Test
	void mixedReadShapesPreserveInvalidBytesAndNeverDuplicateObservations() throws Exception {
		byte[] original = { 0, (byte) 0xff, 13, 10, (byte) 0xe2, (byte) 0x82, (byte) 0xac, (byte) 0xc3, 0x28, 'x' };
		List<byte[]> chunks = new ArrayList<>();
		var input = ObservedStreams.input(new ByteArrayInputStream(original), TransportByteObserver.Stream.STDOUT,
				(stream, bytes) -> chunks.add(bytes));
		assertThat(input.read()).isZero();
		byte[] reused = new byte[4];
		assertThat(input.read(reused)).isEqualTo(4);
		assertThat(input.read(reused, 1, 2)).isEqualTo(2);
		Arrays.fill(reused, (byte) 99);
		assertThat(input.readAllBytes()).containsExactly(-61, 40, 120);
		assertThat(input.read()).isEqualTo(-1);
		assertThat(chunks).hasSize(4);
		assertThat(join(chunks)).isEqualTo(original);
	}

	@Test
	void mixedWriteShapesPreserveExactBytesAndCopies() throws Exception {
		ByteArrayOutputStream destination = new ByteArrayOutputStream();
		List<byte[]> chunks = new ArrayList<>();
		var output = ObservedStreams.output(destination, (stream, bytes) -> chunks.add(bytes));
		byte[] reused = { (byte) 0xff, 13, 10, (byte) 0xc3, 0x28 };
		output.write(0);
		output.write(reused);
		output.write(reused, 2, 3);
		output.write(reused, 0, 0);
		Arrays.fill(reused, (byte) 88);
		output.flush();
		assertThat(chunks).hasSize(3);
		assertThat(join(chunks)).isEqualTo(destination.toByteArray());
		assertThat(join(chunks)).containsExactly(0, -1, 13, 10, -61, 40, 10, -61, 40);
	}

	@Test
	void failedUnderlyingWriteDoesNotClaimSuccessfulObservation() {
		List<byte[]> chunks = new ArrayList<>();
		OutputStream destination = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("broken pipe");
			}
		};
		var output = ObservedStreams.output(destination, (stream, bytes) -> chunks.add(bytes));
		assertThatThrownBy(() -> output.write(new byte[] { 1, 2 })).hasMessage("broken pipe");
		assertThat(chunks).isEmpty();
	}

	private byte[] join(List<byte[]> chunks) throws IOException {
		ByteArrayOutputStream result = new ByteArrayOutputStream();
		for (byte[] chunk : chunks)
			result.write(chunk);
		return result.toByteArray();
	}

	@Test
	void observerFailurePreventsLaterWritesAndDuplicateBytes() throws Exception {
		ByteArrayOutputStream destination = new ByteArrayOutputStream();
		var observed = ObservedStreams.output(destination, (stream, bytes) -> {
			throw new IOException("capture failed after write");
		});
		var writer = new java.io.OutputStreamWriter(observed, java.nio.charset.StandardCharsets.UTF_8);
		writer.write("once");
		assertThatThrownBy(writer::flush).hasMessage("capture failed after write");
		assertThatThrownBy(() -> observed.write(new byte[] { 9 })).hasMessage("capture failed after write");
		writer.close();
		assertThat(destination.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("once");
	}

}
