/*
 * Copyright 2025 Mark Pollack
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

package io.github.markpollack.claude.agent.sdk.transport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.markpollack.claude.agent.sdk.test.ClaudeCliTestBase;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI Flag Parity Integration Test - Uses CLI's --help as the golden standard.
 *
 * <p>
 * This test extracts all available flags from the Claude CLI's --help output and compares
 * them against the Java SDK's CLIOptions builder surface.
 * </p>
 *
 * <p>
 * Background: Two tutorial modules failed due to missing flag support:
 * </p>
 * <ul>
 * <li>Module 09: --json-schema parsing was broken</li>
 * <li>Module 11: --resume flag was completely missing</li>
 * </ul>
 *
 * <h2>Which assertions are gates, and why</h2>
 *
 * <p>
 * This class deliberately runs at two different severities.
 * </p>
 *
 * <p>
 * <strong>{@link #criticalSdkFlagsShouldBeInCli()} is the hard gate.</strong> It verifies
 * that the flags the SDK actually emits still exist in the CLI. If one of those
 * disappears, every session this SDK starts breaks, so a red build is the correct and
 * actionable signal.
 * </p>
 *
 * <p>
 * <strong>{@link #allCliFlagsShouldHaveSdkSupport()} reports, and does not fail.</strong>
 * It used to be a hard ratchet: any flag in {@code --help} without a builder method or an
 * explicit exclusion failed the build. Because CI installs the latest CLI on every run,
 * that made the required build go red whenever Anthropic shipped a new flag — an event
 * that has nothing to do with this repository's code, arrives unannounced, and blocks
 * unrelated work until someone edits this file. An SDK breaks when a flag it <em>uses</em>
 * disappears, not when a flag it <em>ignores</em> appears. New flags are therefore
 * reported as a warning for deliberate triage, and every flag remains reachable today
 * through {@code CLIOptions.extraArgs} regardless.
 * </p>
 */
@DisplayName("CLI Flag Parity IT")
class CLIFlagParityIT extends ClaudeCliTestBase {

	private static Set<String> cliFlags;

	private static String cliHelpOutput;

	/**
	 * Flags the SDK has <strong>permanently declined</strong> to model as builder methods,
	 * each with the reason it was declined.
	 *
	 * <p>
	 * These are settled decisions, not backlog. They are recorded separately from
	 * {@link #EXCLUDED_FLAGS} so that a reader can tell a deliberate "no" apart from a
	 * "not yet". Reviewed against CLI 2.1.235 and re-confirmed against 2.1.246.
	 * </p>
	 *
	 * <p>
	 * Declining does not make a flag unreachable: all of these can still be passed through
	 * {@code CLIOptions.extraArgs} by a consumer who knows what they are doing.
	 * </p>
	 */
	private static final java.util.Map<String, String> DECLINED_FLAGS = java.util.Map.ofEntries(
			java.util.Map.entry("cloud",
					"Creates a Claude-hosted cloud session. The SDK's entire execution model is spawning a "
							+ "local subprocess and speaking stream-JSON to it; a cloud session is not that."),
			java.util.Map.entry("environment",
					"Selects the cloud environment a --cloud session runs on, and is meaningless without it."),
			java.util.Map.entry("teleport",
					"Resumes a teleport session - the same cloud/remote session concept as --cloud."),
			java.util.Map.entry("bg",
					"Starts the session as a background agent, which conflicts with the SDK owning the child "
							+ "process lifecycle, including destroyProcessTree() on close. Adopting it would be a "
							+ "deliberate design change, not a builder method."),
			java.util.Map.entry("background", "Alias of --bg; declined for the same process-lifecycle reason."),
			java.util.Map.entry("ax-screen-reader",
					"Screen-reader friendly rendering for the interactive UI. The SDK always runs "
							+ "--output-format stream-json, so there is no rendering for this to affect."));

	/**
	 * Flags that are intentionally NOT supported by the SDK. Each exclusion must have a
	 * documented reason.
	 */
	private static final Set<String> EXCLUDED_FLAGS = Set.of(
			// Interactive/UI flags - not applicable to SDK usage
			"help", "h", "version", "v", "debug", "d", "print", "p", // SDK always uses
																		// stream-json
																		// mode
			"ide", "chrome", "no-chrome", "disable-slash-commands",

			// Deprecated flags
			"mcp-debug",

			// Session management handled differently in SDK
			"no-session-persistence", "session-id", "replay-user-messages",

			// Security flag that requires special handling
			"allow-dangerously-skip-permissions",

			// Config flags handled via CLIOptions fields
			"strict-mcp-config",

			// Agent selection (different from --agents for custom agents)
			"agent",

			// API configuration
			"betas",

			// Always added by SDK automatically
			"verbose",

			// New CLI flags — passable via extraArgs, first-class builder support TBD
			"effort", // --effort <level> thinking effort (low/medium/high/max)
			"bare", // --bare minimal mode (skip hooks, LSP, etc.)
			"name", "n", // --name / -n session display name
			"worktree", "w", // --worktree / -w git worktree creation
			"brief", // --brief enables SendUserMessage tool
			"file", // --file <specs...> file resources at startup
			"exclude-dynamic-system-prompt-sections", // prompt cache optimization
			"debug-file", // --debug-file <path> debug log output
			"from-pr", // --from-pr resume session from PR
			"remote-control-session-name-prefix", // internal session naming
			"tmux", // --tmux requires worktree, interactive use
			"remote-control", // --remote-control remote control API (Slack/remote interfaces), not SDK-relevant
			"plugin-url", // --plugin-url URL-based plugin loading, passable via extraArgs, first-class TBD
			"prompt-suggestions" // --prompt-suggestions interactive prompt suggestions (interactive UI, not SDK-relevant)

	// NOTE: --forward-subagent-text, --include-hook-events, --autocompact and
	// --safe-mode were previously listed here as backlog. They are now first-class
	// CLIOptions builder methods (forwardSubagentText, includeHookEvents,
	// autocompact, safeMode) and are resolved by the normal builder lookup below.
	//
	// NOTE: --cloud, --environment, --teleport, --bg/--background and
	// --ax-screen-reader moved to DECLINED_FLAGS above, which records why each was
	// declined rather than leaving them looking pending.
	);

	/**
	 * Every flag the SDK does not map to a builder method, whether declined permanently or
	 * excluded for the operational reasons above.
	 */
	private static Set<String> notMappedByDesign() {
		Set<String> all = new HashSet<>(EXCLUDED_FLAGS);
		all.addAll(DECLINED_FLAGS.keySet());
		return all;
	}

	/**
	 * Mapping from CLI flag names to CLIOptions builder method names. Only needed when
	 * names don't match directly.
	 */
	private static final java.util.Map<String, String> FLAG_TO_METHOD = java.util.Map.ofEntries(
			java.util.Map.entry("continue", "continueConversation"), java.util.Map.entry("c", "continueConversation"),
			java.util.Map.entry("r", "resume"), java.util.Map.entry("allowed-tools", "allowedTools"),
			java.util.Map.entry("disallowed-tools", "disallowedTools"), java.util.Map.entry("add-dir", "addDirs"),
			java.util.Map.entry("plugin-dir", "plugins"), java.util.Map.entry("mcp-config", "mcpServers"),
			java.util.Map.entry("output-format", "outputFormat"), java.util.Map.entry("input-format", "outputFormat"), // Handled
																														// internally
			java.util.Map.entry("system-prompt", "systemPrompt"),
			java.util.Map.entry("append-system-prompt", "appendSystemPrompt"),
			java.util.Map.entry("json-schema", "jsonSchema"), java.util.Map.entry("max-budget-usd", "maxBudgetUsd"),
			java.util.Map.entry("max-thinking-tokens", "maxThinkingTokens"),
			java.util.Map.entry("permission-mode", "permissionMode"),
			java.util.Map.entry("permission-prompt-tool", "permissionPromptToolName"),
			java.util.Map.entry("fallback-model", "fallbackModel"), java.util.Map.entry("fork-session", "forkSession"),
			java.util.Map.entry("include-partial-messages", "includePartialMessages"),
			java.util.Map.entry("setting-sources", "settingSources"), java.util.Map.entry("max-turns", "maxTurns"),
			java.util.Map.entry("dangerously-skip-permissions", "permissionMode") // Handled
																					// via
																					// PermissionMode
																					// enum
	);

	@BeforeAll
	static void extractCliFlagsFromHelp() throws Exception {
		ProcessBuilder pb = new ProcessBuilder("claude", "--help");
		pb.redirectErrorStream(true);
		Process process = pb.start();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
			cliHelpOutput = reader.lines().collect(Collectors.joining("\n"));
		}

		int exitCode = process.waitFor();
		assertThat(exitCode).as("claude --help should succeed").isZero();

		cliFlags = parseFlags(cliHelpOutput);
	}

	/**
	 * Parses flag names from CLI help output. Matches patterns like: -c, --continue,
	 * --model <model>, --tools <tools...>
	 */
	private static Set<String> parseFlags(String helpOutput) {
		Set<String> flags = new HashSet<>();

		// Pattern to match flags: -x, --flag-name, --flag-name <value>
		Pattern pattern = Pattern.compile("--([a-zA-Z][a-zA-Z0-9-]*)");
		Matcher matcher = pattern.matcher(helpOutput);

		while (matcher.find()) {
			flags.add(matcher.group(1));
		}

		// Also extract short flags
		Pattern shortPattern = Pattern.compile("\\s-([a-zA-Z]),");
		Matcher shortMatcher = shortPattern.matcher(helpOutput);
		while (shortMatcher.find()) {
			flags.add(shortMatcher.group(1));
		}

		return flags;
	}

	/**
	 * Reports CLI flags with no builder method and no recorded decision.
	 *
	 * <p>
	 * <strong>This assertion is deliberately a warning, not a gate.</strong> See the class
	 * javadoc: a newly shipped CLI flag is not a defect in this SDK, and failing the
	 * required build on one turns an upstream release into an unrelated red build here.
	 * The hard gate is {@link #criticalSdkFlagsShouldBeInCli()}.
	 * </p>
	 */
	@Test
	@DisplayName("Unsupported CLI flags are reported for triage (warning, not a gate)")
	void allCliFlagsShouldHaveSdkSupport() {
		Set<String> builderMethods = getBuilderMethodNames();
		Set<String> notMapped = notMappedByDesign();
		Set<String> unsupportedFlags = new HashSet<>();

		for (String flag : cliFlags) {
			if (notMapped.contains(flag)) {
				continue; // Intentionally excluded or permanently declined
			}

			String methodName = FLAG_TO_METHOD.getOrDefault(flag, toCamelCase(flag));

			if (!builderMethods.contains(methodName)) {
				unsupportedFlags.add(flag);
			}
		}

		if (!unsupportedFlags.isEmpty()) {
			System.out.println("=== WARNING: CLI flags with no SDK decision ===");
			unsupportedFlags.stream().sorted().forEach(flag -> System.out.println("  --" + flag));
			System.out.println("These are reachable today via CLIOptions.extraArgs. To resolve one, either add a "
					+ "CLIOptions builder method, add it to EXCLUDED_FLAGS with a reason, or add it to "
					+ "DECLINED_FLAGS with the reason it will never be modelled.");
			System.out.println("Not failing the build: see CLIFlagParityIT javadoc.");
		}
	}

	@Test
	@DisplayName("Declined flags each record a reason and stay unmodelled")
	void declinedFlagsAreDocumentedAndUnmodelled() {
		Set<String> builderMethods = getBuilderMethodNames();

		assertThat(DECLINED_FLAGS).as("Declined flags must be recorded with their reason").isNotEmpty();

		DECLINED_FLAGS.forEach((flag, reason) -> {
			assertThat(reason).as("Declined flag --" + flag + " must record why it was declined")
				.isNotBlank()
				.hasSizeGreaterThan(30);

			String methodName = FLAG_TO_METHOD.getOrDefault(flag, toCamelCase(flag));
			assertThat(builderMethods)
				.as("--" + flag + " is recorded as permanently declined but a builder method '" + methodName
						+ "' now exists. Move it out of DECLINED_FLAGS if the decision changed.")
				.doesNotContain(methodName);
		});
	}

	@Test
	@DisplayName("Newly promoted flags are modelled as builder methods")
	void promotedFlagsHaveBuilderMethods() {
		Set<String> builderMethods = getBuilderMethodNames();

		assertThat(builderMethods).as("Flags promoted out of the backlog must have first-class builder methods")
			.contains("forwardSubagentText", "includeHookEvents", "autocompact", "safeMode");

		assertThat(notMappedByDesign())
			.as("Promoted flags must no longer be recorded as excluded or declined")
			.doesNotContain("forward-subagent-text", "include-hook-events", "autocompact", "safe-mode");
	}

	@Test
	@DisplayName("CLI help should be parseable")
	void cliHelpShouldBeParseable() {
		assertThat(cliFlags).isNotEmpty();
		assertThat(cliFlags).contains("model", "resume", "continue", "json-schema");
	}

	/**
	 * <strong>This is the hard gate of this class.</strong> These flags are emitted by
	 * {@code StreamingTransport.buildStreamingCommand}, so if the CLI drops one, every
	 * session the SDK starts fails on an unknown argument. That is a real break and a red
	 * build is the correct signal.
	 */
	@Test
	@DisplayName("Critical SDK flags should be in CLI")
	void criticalSdkFlagsShouldBeInCli() {
		// These are flags we know we support - verify CLI still has them
		// Note: Some flags like max-turns and max-thinking-tokens work but aren't in
		// --help
		List<String> criticalFlags = List.of("model", "system-prompt", "allowedTools", "disallowedTools",
				"permission-mode", "resume", "continue", "json-schema", "max-budget-usd", "agents", "mcp-config",
				"fallback-model", "fork-session", "settings",
				// Promoted from the backlog and now emitted by the SDK.
				"forward-subagent-text", "include-hook-events", "autocompact", "safe-mode");

		for (String flag : criticalFlags) {
			assertThat(cliFlags).as("CLI should support flag: " + flag).contains(flag);
		}
	}

	@Test
	@DisplayName("Report CLI flags found for documentation")
	void reportCliFlagsFound() {
		System.out.println("=== CLI Flags Found ===");
		cliFlags.stream().sorted().forEach(flag -> {
			String methodName = FLAG_TO_METHOD.getOrDefault(flag, toCamelCase(flag));
			String status;
			if (DECLINED_FLAGS.containsKey(flag)) {
				status = " [DECLINED] " + DECLINED_FLAGS.get(flag);
			}
			else if (EXCLUDED_FLAGS.contains(flag)) {
				status = " [EXCLUDED]";
			}
			else {
				status = "";
			}
			System.out.printf("  --%s -> %s%s%n", flag, methodName, status);
		});
		System.out.println("Total: " + cliFlags.size() + " flags");
		System.out.println("Excluded: " + EXCLUDED_FLAGS.size() + " flags");
		System.out.println("Declined: " + DECLINED_FLAGS.size() + " flags");
	}

	/**
	 * Gets all builder method names from CLIOptions.Builder.
	 */
	private Set<String> getBuilderMethodNames() {
		Set<String> methods = new HashSet<>();
		for (Method method : CLIOptions.Builder.class.getDeclaredMethods()) {
			if (method.getReturnType().equals(CLIOptions.Builder.class)) {
				methods.add(method.getName());
			}
		}
		return methods;
	}

	/**
	 * Converts kebab-case to camelCase.
	 */
	private String toCamelCase(String kebab) {
		StringBuilder sb = new StringBuilder();
		boolean capitalizeNext = false;
		for (char c : kebab.toCharArray()) {
			if (c == '-') {
				capitalizeNext = true;
			}
			else if (capitalizeNext) {
				sb.append(Character.toUpperCase(c));
				capitalizeNext = false;
			}
			else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

}
