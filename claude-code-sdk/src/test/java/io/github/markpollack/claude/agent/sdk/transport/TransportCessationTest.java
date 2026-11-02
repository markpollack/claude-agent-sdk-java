/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class TransportCessationTest {
    @TempDir Path dir;
    enum Entry { TRANSPORT, GRACEFUL, SYNC, ASYNC }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void closesTermIgnoringClosedStdioDescendantAndRetainsReceipt(Entry entry) throws Exception {
        Path cli = python("""
                pid = os.fork()
                if pid == 0:
                    signal.signal(signal.SIGTERM, signal.SIG_IGN)
                    for fd in (0, 1, 2):
                        os.close(fd)
                    (root / 'descendant.pid').write_text(str(os.getpid()))
                    time.sleep(60)
                    os._exit(0)
                (root / 'root.pid').write_text(str(os.getpid()))
                while True: time.sleep(1)
                """);
        List<ProcessHandle> cleanup = new ArrayList<>();
        StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(5), cli.toString());
        ClaudeSyncClient sync = ClaudeClient.sync().workingDirectory(dir).claudePath(cli.toString()).build();
        var async = ClaudeClient.async().workingDirectory(dir).claudePath(cli.toString()).build();
        try {
            if (entry == Entry.SYNC) sync.connect();
            else if (entry == Entry.ASYNC) async.connect().block(Duration.ofSeconds(5));
            else transport.startSession(null, CLIOptions.builder().build(), p -> {}, null);
            long root = pid("root.pid"), descendant = pid("descendant.pid");
            cleanup.add(ProcessHandle.of(root).orElseThrow());
            cleanup.add(ProcessHandle.of(descendant).orElseThrow());
            long started = System.nanoTime();
            if (entry == Entry.SYNC) sync.close();
            else if (entry == Entry.ASYNC) async.close().block(Duration.ofSeconds(9));
            else if (entry == Entry.GRACEFUL) transport.closeGracefully().block(Duration.ofSeconds(9));
            else transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(9));
            TransportResourceSnapshot receipt = entry == Entry.SYNC ? sync.transportResources().orElseThrow()
                    : entry == Entry.ASYNC ? async.transportResources().orElseThrow() : transport.resourceSnapshot();
            assertThat(receipt.processes()).extracting(TransportResourceSnapshot.OwnedProcess::pid).contains(root, descendant);
            assertThat(receipt.processes()).allMatch(p -> p.state() == TransportResourceSnapshot.ProcessState.EXITED);
            assertThat(receipt.processes()).allMatch(p -> p.startedAt().isPresent());
            assertThat(receipt.observedResourcesTerminated()).as(receipt.toString()).isTrue();
            assertThat(receipt.stdout().eof()).isTrue();
            assertThat(receipt.stderr().eof()).isTrue();
            assertThatThrownBy(() -> receipt.processes().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(cleanup).noneMatch(ProcessHandle::isAlive);
        }
        finally {
            for (ProcessHandle p : cleanup) if (p.isAlive()) { p.destroyForcibly(); p.onExit().get(3, TimeUnit.SECONDS); }
            transport.close(); sync.close(); async.close().block(Duration.ofSeconds(9));
        }
    }

    @Test
    void forciblyClosesStubbornRootWithinOneBudget() throws Exception {
        Path cli = python("signal.signal(signal.SIGTERM, signal.SIG_IGN)\n(root / 'root.pid').write_text(str(os.getpid()))\nwhile True: time.sleep(1)\n");
        try (StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(20), cli.toString())) {
            transport.startSession(null, CLIOptions.builder().build(), p -> {}, null);
            long pid = pid("root.pid");
            transport.close();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(transport.resourceSnapshot().observedResourcesTerminated()).isTrue();
            long started = System.nanoTime(); transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
        }
    }

    @Test
    void blockedObserverIsUnsettledAndRepeatedCloseDoesNotRenewAllowance() throws Exception {
        Path cli = python("os.write(1, b'pending\\n')\n(root / 'root.pid').write_text(str(os.getpid()))\nwhile True: time.sleep(1)\n");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(20), cli.toString());
        try {
            transport.startSession(null, CLIOptions.builder().transportByteObserver((stream, bytes) -> {
                if (stream != TransportByteObserver.Stream.STDOUT) return;
                entered.countDown();
                boolean interrupted = false;
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }).build(), p -> {}, null);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            pid("root.pid");
            long started = System.nanoTime();
            transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(6), Duration.ofSeconds(9));
            var unsettled = transport.resourceSnapshot();
            assertThat(unsettled.observedResourcesTerminated()).isFalse();
            assertThat(unsettled.stdout().finished()).isFalse();
            assertThat(unsettled.stdout().eof()).isFalse();
            assertThat(unsettled.closeWorkerFinished()).isFalse();
            assertThat(transport.getSessionError()).hasMessage("Transport cleanup allowance expired");
            assertThat(unsettled.processes()).allMatch(p -> p.state() == TransportResourceSnapshot.ProcessState.EXITED);
            started = System.nanoTime(); transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
            release.countDown();
            await(() -> transport.resourceSnapshot().observedResourcesTerminated());
            assertThat(unsettled.stdout().finished()).as("prior snapshot stays detached").isFalse();
        }
        finally {
            release.countDown(); transport.close();
            await(() -> transport.resourceSnapshot().closeWorkerFinished());
        }
    }

    @Test
    void observerFailureIsPumpCompletionNotEof() throws Exception {
        Path cli = python("os.write(2, b'evidence\\n')\nwhile True: time.sleep(1)\n");
        try (StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(10), cli.toString())) {
            transport.startSession(null, CLIOptions.builder().transportByteObserver((stream, bytes) -> {
                if (stream == TransportByteObserver.Stream.STDERR) throw new IOException("refused capture");
            }).build(), p -> {}, null);
            await(() -> transport.getSessionError() != null);
            transport.close();
            assertThat(transport.resourceSnapshot().stderr().finished()).isTrue();
            assertThat(transport.resourceSnapshot().stderr().eof()).isFalse();
            assertThat(transport.resourceSnapshot().observedResourcesTerminated()).isTrue();
            assertThat(transport.getSessionError()).hasRootCauseMessage("refused capture");
        }
    }

    @Test
    void partialSetupFailureClosesAcquiredProcessWithoutReplacingCause() throws Exception {
        Path cli = python("while True: time.sleep(1)\n");
        StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(10), cli.toString());
        // Fail the scheduler after OS process acquisition, exercising actual partial cleanup.
        var field = StreamingTransport.class.getDeclaredField("inboundExecutor"); field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(transport)).shutdownNow();
        try {
            assertThatThrownBy(() -> transport.startSession(null, CLIOptions.builder().build(), p -> {}, null))
                    .isInstanceOf(TransportException.class).hasCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            var receipt = transport.resourceSnapshot();
            assertThat(receipt.processes()).hasSize(1);
            assertThat(receipt.observedResourcesTerminated()).as(receipt.toString()).isTrue();
            assertThat(receipt.stdout().eof()).isFalse();
            assertThat(receipt.stderr().eof()).isFalse();
        }
        finally { transport.close(); }
    }

    @Test
    void concurrentCallersShareTheSameAttemptAndInterruptIsRestored() throws Exception {
        Path cli = python("(root / 'root.pid').write_text(str(os.getpid()))\nwhile True: time.sleep(1)\n");
        try (StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(10), cli.toString());
                var executor = Executors.newFixedThreadPool(3)) {
            transport.startSession(null, CLIOptions.builder().build(), p -> {}, null); pid("root.pid");
            String identity = transport.resourceSnapshot().connectionId();
            var one = executor.submit(transport::close);
            var two = executor.submit(() -> { Thread.currentThread().interrupt(); transport.close(); return Thread.interrupted(); });
            var three = executor.submit(transport::close);
            one.get(9, TimeUnit.SECONDS); three.get(9, TimeUnit.SECONDS);
            assertThat(two.get(9, TimeUnit.SECONDS)).isTrue();
            assertThat(transport.resourceSnapshot().connectionId()).isEqualTo(identity);
            assertThat(transport.resourceSnapshot().observedResourcesTerminated()).isTrue();
        }
    }

    @Test
    void refusedProcessTerminationIsExplicitRatherThanSuccessfulClose() throws Exception {
        var refusing = org.mockito.Mockito.mock(ProcessHandle.class);
        org.mockito.Mockito.when(refusing.pid()).thenReturn(Long.MAX_VALUE);
        org.mockito.Mockito.when(refusing.isAlive()).thenReturn(true);
        org.mockito.Mockito.when(refusing.descendants()).thenAnswer(call -> java.util.stream.Stream.empty());
        var info = org.mockito.Mockito.mock(ProcessHandle.Info.class);
        org.mockito.Mockito.when(info.startInstant()).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(refusing.info()).thenReturn(info);
        Path cli = python("(root / 'root.pid').write_text(str(os.getpid()))\nwhile True: time.sleep(1)\n");
        try (StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(10), cli.toString())) {
            transport.startSession(null, CLIOptions.builder().build(), p -> {}, null); pid("root.pid");
            var field = StreamingTransport.class.getDeclaredField("ownedProcesses"); field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var inventory = (java.util.concurrent.ConcurrentMap<Long, ProcessHandle>) field.get(transport);
            inventory.put(Long.MAX_VALUE, refusing);
            transport.close();
            var receipt = transport.resourceSnapshot();
            assertThat(receipt.closeWorkerFinished()).isTrue();
            assertThat(receipt.observedResourcesTerminated()).isFalse();
            assertThat(receipt.processes()).anyMatch(p -> p.pid() == Long.MAX_VALUE && p.state() == TransportResourceSnapshot.ProcessState.ALIVE);
            assertThat(receipt.issues()).contains("Observed process exit remains unconfirmed after escalation");
            org.mockito.Mockito.verify(refusing, org.mockito.Mockito.atLeastOnce()).destroyForcibly();
        }
    }

    private Path python(String body) throws IOException {
        Path file = dir.resolve("child.py");
        Files.writeString(file, "#!/usr/bin/env python3\nimport os, signal, time\nfrom pathlib import Path\nroot = Path(__file__).resolve().parent\n" + body);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    private long pid(String name) throws Exception {
        Path file = dir.resolve(name);
        await(() -> { try { return Files.exists(file) && !Files.readString(file).isBlank(); } catch (IOException e) { return false; } });
        return Long.parseLong(Files.readString(file).strip());
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
