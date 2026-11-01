/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** Stream decorators below the UTF-8 readers/writer. */
final class ObservedStreams {

	private ObservedStreams() {
	}

	static InputStream input(InputStream source, TransportByteObserver.Stream stream, TransportByteObserver observer) {
		return new FilterInputStream(source) {
			@Override
			public int read() throws IOException {
				int value = in.read();
				if (value != -1) {
					observer.onBytes(stream, new byte[] { (byte) value });
				}
				return value;
			}

			@Override
			public int read(byte[] bytes, int offset, int length) throws IOException {
				int count = in.read(bytes, offset, length);
				if (count > 0) {
					observer.onBytes(stream, Arrays.copyOfRange(bytes, offset, offset + count));
				}
				return count;
			}
		};
	}

	static OutputStream output(OutputStream destination, TransportByteObserver observer) {
		return new FilterOutputStream(destination) {
			private IOException failure;

			@Override
			public void write(int value) throws IOException {
				write(new byte[] { (byte) value }, 0, 1);
			}

			@Override
			public void write(byte[] bytes, int offset, int length) throws IOException {
				if (failure != null) {
					throw failure;
				}
				try {
					// Delegate directly: FilterOutputStream loops over write(int).
					out.write(bytes, offset, length);
					if (length > 0) {
						observer.onBytes(TransportByteObserver.Stream.STDIN,
								Arrays.copyOfRange(bytes, offset, offset + length));
					}
				}
				catch (IOException e) {
					// Later writes must not resume a failed stream or resend an
					// unknown or already-written prefix after write/observation failure.
					failure = e;
					throw e;
				}
			}
		};
	}

}
