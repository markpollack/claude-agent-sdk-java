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
progressive runnable modules, from the Query API through multi-agent orchestration.

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

`clean verify` runs the deterministic unit suite and then the integration tests, which
drive a real Claude CLI and consume model usage. To build without them:

```bash
./mvnw clean verify -DskipITs
```

`scripts/standalone-consumer-gate.sh` checks the shape an ordinary consumer receives —
dependency floors, published POM, Java 21 artifact shape — with no credentials and no
model calls.

## Compatibility

Verified against Claude Code CLI 2.1.235 for the capabilities the SDK exposes. The CLI
moves faster than this SDK models it: flags it has added recently — `--cloud`,
`--teleport`, `--environment`, `--bg`/`--background`, `--safe-mode`, `--autocompact`,
`--forward-subagent-text`, `--ax-screen-reader` — have no first-class builder method and
are reachable through `extraArgs`.

## License

[Apache License 2.0](LICENSE).
