/*
 * Copyright 2026 Mark Pollack
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

package io.github.markpollack.claude.agent.sdk.hooks;

import io.github.markpollack.claude.agent.sdk.ClaudeAsyncClient;
import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.config.PermissionMode;
import io.github.markpollack.claude.agent.sdk.test.ClaudeCliTestBase;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.Message;
import io.github.markpollack.claude.agent.sdk.types.ToolResultBlock;
import io.github.markpollack.claude.agent.sdk.types.UserMessage;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput.HookSpecificOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, against the real CLI, that the decisions a hook returns through
 * {@link HookOutput#hookSpecificOutput()} take effect: a PreToolUse deny stops the tool,
 * an {@code updatedInput} replaces its input, and {@code additionalContext} reaches the
 * model.
 *
 * <p>
 * The sessions run in {@link PermissionMode#BYPASS_PERMISSIONS}, so the hook is the only
 * thing that can stop a tool. In a prompting mode the CLI could deny the tool on its own
 * and a broken hook would still look like it worked.
 * </p>
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@Tag("live")
class HookDecisionIT extends ClaudeCliTestBase {

	private static final String HAIKU_MODEL = CLIOptions.MODEL_HAIKU;

	private static final String DENY_REASON = "touch is blocked by the HookDecisionIT policy";

	private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(2);

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("A PreToolUse deny stops the Bash command")
	void preToolUseDenyBlocksBash() throws Exception {
		Path marker = tempDir.resolve("denied.txt");
		AtomicInteger hookCalls = new AtomicInteger();

		List<Message> messages = runSync(denyBash(hookCalls), touchPrompt(marker));

		assertDenied(hookCalls, marker, messages);
	}

	@Test
	@DisplayName("Async client: a PreToolUse deny stops the Bash command")
	void asyncPreToolUseDenyBlocksBash() {
		Path marker = tempDir.resolve("denied-async.txt");
		AtomicInteger hookCalls = new AtomicInteger();

		List<Message> messages = runAsync(denyBash(hookCalls), touchPrompt(marker));

		assertDenied(hookCalls, marker, messages);
	}

	@Test
	@DisplayName("A PreToolUse updatedInput replaces the Bash command")
	void preToolUseUpdatedInputReplacesCommand() throws Exception {
		Path requested = tempDir.resolve("requested.txt");
		Path rewritten = tempDir.resolve("rewritten.txt");
		HookRegistry hooks = new HookRegistry();
		hooks.registerPreToolUse("Bash",
				input -> HookOutput.builder()
					.hookSpecificOutput(HookSpecificOutput.preToolUseModify(Map.of("command", "touch " + rewritten)))
					.build());

		runSync(hooks, touchPrompt(requested));

		assertThat(rewritten).as("the rewritten command should have run").exists();
		assertThat(requested).as("the command the model asked for should have been replaced").doesNotExist();
	}

	@Test
	@DisplayName("UserPromptSubmit additionalContext reaches the model")
	void userPromptSubmitAdditionalContextReachesModel() throws Exception {
		HookRegistry hooks = new HookRegistry();
		hooks.registerUserPromptSubmit(input -> HookOutput.builder()
			.hookSpecificOutput(HookSpecificOutput.userPromptSubmit("The codeword for this session is PELICAN-7731."))
			.build());

		List<Message> messages = runSync(hooks,
				"What is the codeword for this session? Reply with just the codeword, or NONE if you were not given one.");

		String text = messages.stream()
			.filter(AssistantMessage.class::isInstance)
			.map(m -> ((AssistantMessage) m).text())
			.collect(Collectors.joining());
		assertThat(text).contains("PELICAN-7731");
	}

	private static HookRegistry denyBash(AtomicInteger hookCalls) {
		HookRegistry hooks = new HookRegistry();
		hooks.registerPreToolUse("Bash", input -> {
			hookCalls.incrementAndGet();
			return HookOutput.builder().hookSpecificOutput(HookSpecificOutput.preToolUseDeny(DENY_REASON)).build();
		});
		return hooks;
	}

	private static void assertDenied(AtomicInteger hookCalls, Path marker, List<Message> messages) {
		assertThat(hookCalls).as("the PreToolUse hook should have been called").hasPositiveValue();
		assertThat(marker).as("the denied command must not have run").doesNotExist();
		assertThat(toolResults(messages)).as("the CLI reports the deny as an errored tool result")
			.anySatisfy(result -> {
				assertThat(result.isError()).isTrue();
				assertThat(String.valueOf(result.content())).contains(DENY_REASON);
			});
	}

	private List<Message> runSync(HookRegistry hooks, String prompt) {
		List<Message> messages = new ArrayList<>();
		try (ClaudeSyncClient client = ClaudeClient.sync()
			.workingDirectory(tempDir)
			.claudePath(getClaudeCliPath())
			.model(HAIKU_MODEL)
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.hookRegistry(hooks)
			.timeout(SESSION_TIMEOUT)
			.build()) {
			client.connectAndReceive(prompt).forEach(messages::add);
		}
		return messages;
	}

	private List<Message> runAsync(HookRegistry hooks, String prompt) {
		ClaudeAsyncClient client = ClaudeClient.async()
			.workingDirectory(tempDir)
			.claudePath(getClaudeCliPath())
			.model(HAIKU_MODEL)
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.hookRegistry(hooks)
			.timeout(SESSION_TIMEOUT)
			.build();
		try {
			return client.connect(prompt).messages().collectList().block(SESSION_TIMEOUT);
		}
		finally {
			client.close().block(Duration.ofSeconds(30));
		}
	}

	private static String touchPrompt(Path file) {
		return "Use the Bash tool to run exactly this command, once, and do not try anything else afterwards: touch "
				+ file;
	}

	private static List<ToolResultBlock> toolResults(List<Message> messages) {
		return messages.stream()
			.filter(UserMessage.class::isInstance)
			.map(message -> ((UserMessage) message).getContentAsBlocks())
			.filter(Objects::nonNull)
			.flatMap(List::stream)
			.filter(ToolResultBlock.class::isInstance)
			.map(ToolResultBlock.class::cast)
			.toList();
	}

}
