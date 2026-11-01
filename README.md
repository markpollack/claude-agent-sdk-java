# Claude Agent SDK for Java

A Java SDK for driving the [Claude Code CLI](https://docs.anthropic.com/en/docs/agents-and-tools/claude-code/overview)
as a subprocess. It offers three APIs over the same transport — `Query` for one-shot
calls, `ClaudeSyncClient` for blocking multi-turn sessions, and `ClaudeAsyncClient` for
reactive use with Project Reactor — along with hooks, permission callbacks, MCP server
integration and subagent definitions. Pure Java, no native dependencies.

## Documentation

**[lab.pollack.ai/projects/claude-agent-sdk](https://lab.pollack.ai/projects/claude-agent-sdk)** —
canonical documentation: architecture, API guides, release history and how the SDK fits
the rest of the lab.

**[Tutorial](https://github.com/markpollack/claude-agent-sdk-java-tutorial)** — 23
progressive runnable modules, from the Query API through multi-agent orchestration. It also
hosts [Java ports of Anthropic's official SDK demos](https://github.com/markpollack/claude-agent-sdk-java-tutorial/tree/main/ports/anthropic-demos)
— hello-world, research-agent, excel-demo and email-agent — which used to live in this
repository's `examples/` directory.

## Installation

Released artifacts are on
[Maven Central](https://central.sonatype.com/artifact/io.github.markpollack/claude-code-sdk).

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>claude-code-sdk</artifactId>
    <version>1.4.0</version>
</dependency>
```

## Requirements

- **Java 21 or later.** Every published artifact is Java 21 bytecode (class-file major
  65); there is no Java 17 build.
- Claude Code CLI installed and authenticated.
- Maven 3.8+ to build from source.

## Building

```bash
git clone https://github.com/markpollack/claude-agent-sdk-java.git
cd claude-agent-sdk-java
./mvnw clean verify
```

`clean verify` is deterministic and free: it runs the unit suite plus the credential-free
integration tests. It needs the Claude CLI binary on the PATH, but makes no authenticated
or paid calls.

The integration tests that drive a real Claude CLI against the Anthropic API are tagged
`live` and are **excluded by default**. To include them — this requires
`ANTHROPIC_API_KEY` and consumes model usage:

```bash
./mvnw clean verify -Dfailsafe.excluded.groups=
```

`scripts/standalone-consumer-gate.sh` checks the shape an ordinary consumer receives —
dependency floors, published POM, Java 21 artifact shape — with no credentials and no
model calls.

See [RELEASING.md](RELEASING.md) for which checks gate a release and when a live run is
required.

## Compatibility

Verified against Claude Code CLI 2.1.235 for the capabilities the SDK exposes, and
re-confirmed against 2.1.246.

`--forward-subagent-text`, `--include-hook-events`, `--autocompact` and `--safe-mode` now
have first-class builder methods (`forwardSubagentText`, `includeHookEvents`,
`autocompact`, `safeMode`).

`--cloud`, `--environment`, `--teleport`, `--bg`/`--background` and `--ax-screen-reader`
are deliberately **not** modelled. The first four are a different execution model — a
cloud/remote session, or a process the SDK does not own the lifecycle of — and the last is
interactive-UI-only, while the SDK always runs `--output-format stream-json`. Each decline
is recorded with its reason in `CLIFlagParityIT.DECLINED_FLAGS`. All of them, and any flag
the CLI ships that this SDK has not modelled, remain reachable through
`CLIOptions.extraArgs`.

## License

[Apache License 2.0](LICENSE).

## Observing process bytes

An optional per-session observer receives original process stdout/stderr before UTF-8
decoding and stdin after each successful encoded write:

```java
CLIOptions options = CLIOptions.builder()
    .transportByteObserver((stream, bytes) -> recorder.append(stream, bytes))
    .build();
```

`TransportByteObserver` and `CLIOptions` are in
`io.github.markpollack.claude.agent.sdk.transport`. Its `Stream` values are `STDIN`,
`STDOUT`, and `STDERR`; `onBytes(Stream, byte[])` may throw `IOException`. Each nonempty
array belongs to the observer and may be retained or modified. Callbacks run synchronously,
in order within each stream, and may run concurrently across streams. Return promptly;
do not call SDK lifecycle methods from a callback. Chunks are not protocol frames and a
successful stdin write does not prove the child consumed it.

The caller owns recording files, offsets, timestamps, final flushing/closing and capture
status. Latch recording errors before throwing from the callback and check that latch
and final flush/close results before sealing a recording, including when using
`ClaudeSyncClient`. The transport retains observer failures through `getSessionError()`;
a successful model response alone does not establish complete capture. Cancellation can
truncate output, and the hook makes no durability or native-session acquisition guarantee.
With an observer, normal process completion drains both readers before
`waitForCompletion` succeeds. Closing terminates the process first and waits for readers;
blocking caller callbacks can delay shutdown.

`rawLineListener` remains the decoded, terminator-free stdout API. The byte observer is
independent of it and of typed message coverage. Adding the observer component changes
`CLIOptions`' canonical record constructor and record patterns; builder callers retain
the existing construction path.
