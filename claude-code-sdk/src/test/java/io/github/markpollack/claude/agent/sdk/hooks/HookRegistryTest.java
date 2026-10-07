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

package io.github.markpollack.claude.agent.sdk.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.github.markpollack.claude.agent.sdk.types.control.ControlRequest;
import io.github.markpollack.claude.agent.sdk.types.control.ControlResponse;
import io.github.markpollack.claude.agent.sdk.types.control.HookEvent;
import io.github.markpollack.claude.agent.sdk.types.control.HookInput;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for HookRegistry - registration, execution, and configuration building.
 */
class HookRegistryTest {

	private HookRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new HookRegistry();
	}

	@Nested
	@DisplayName("Hook Registration")
	class HookRegistrationTests {

		@Test
		@DisplayName("Should register PreToolUse hook with pattern")
		void registerPreToolUseWithPattern() {
			// When
			String id = registry.registerPreToolUse("Bash|Write", input -> HookOutput.allow());

			// Then
			assertThat(id).startsWith("hook_");
			assertThat(registry.hasHooks()).isTrue();
			assertThat(registry.getById(id)).isNotNull();
			assertThat(registry.getById(id).event()).isEqualTo(HookEvent.PRE_TOOL_USE);
		}

		@Test
		@DisplayName("Should register PreToolUse hook for all tools")
		void registerPreToolUseForAllTools() {
			// When
			String id = registry.registerPreToolUse(input -> HookOutput.allow());

			// Then
			HookRegistration reg = registry.getById(id);
			assertThat(reg.toolPattern()).isNull();
			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Read")).isTrue();
		}

		@Test
		@DisplayName("Should register PostToolUse hook")
		void registerPostToolUse() {
			// When
			String id = registry.registerPostToolUse("Read", input -> HookOutput.allow());

			// Then
			assertThat(registry.getById(id).event()).isEqualTo(HookEvent.POST_TOOL_USE);
		}

		@Test
		@DisplayName("Should register UserPromptSubmit hook")
		void registerUserPromptSubmit() {
			// When
			String id = registry.registerUserPromptSubmit(input -> HookOutput.allow());

			// Then
			assertThat(registry.getById(id).event()).isEqualTo(HookEvent.USER_PROMPT_SUBMIT);
		}

		@Test
		@DisplayName("Should register Stop hook")
		void registerStop() {
			// When
			String id = registry.registerStop(input -> HookOutput.allow());

			// Then
			assertThat(registry.getById(id).event()).isEqualTo(HookEvent.STOP);
		}

		@Test
		@DisplayName("Should reject duplicate hook IDs")
		void rejectDuplicateId() {
			// Given
			HookRegistration reg1 = HookRegistration.preToolUse("my_hook", input -> HookOutput.allow());
			registry.register(reg1);

			// When/Then
			HookRegistration reg2 = HookRegistration.preToolUse("my_hook", input -> HookOutput.block("test"));
			assertThatThrownBy(() -> registry.register(reg2)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("already registered");
		}

		@Test
		@DisplayName("Should unregister hook by ID")
		void unregisterById() {
			// Given
			String id = registry.registerPreToolUse(input -> HookOutput.allow());

			// When
			boolean removed = registry.unregister(id);

			// Then
			assertThat(removed).isTrue();
			assertThat(registry.getById(id)).isNull();
			assertThat(registry.hasHooks()).isFalse();
		}

		@Test
		@DisplayName("Should return false when unregistering non-existent hook")
		void unregisterNonExistent() {
			assertThat(registry.unregister("non_existent")).isFalse();
		}

		@Test
		@DisplayName("Should clear all hooks")
		void clearAllHooks() {
			// Given
			registry.registerPreToolUse(input -> HookOutput.allow());
			registry.registerPostToolUse(input -> HookOutput.allow());
			registry.registerStop(input -> HookOutput.allow());

			// When
			registry.clear();

			// Then
			assertThat(registry.hasHooks()).isFalse();
			assertThat(registry.getRegisteredIds()).isEmpty();
		}

		@Test
		@DisplayName("Should get hooks by event type")
		void getByEvent() {
			// Given
			registry.registerPreToolUse("Bash", input -> HookOutput.allow());
			registry.registerPreToolUse("Write", input -> HookOutput.allow());
			registry.registerPostToolUse(input -> HookOutput.allow());

			// When
			List<HookRegistration> preToolUseHooks = registry.getByEvent(HookEvent.PRE_TOOL_USE);
			List<HookRegistration> postToolUseHooks = registry.getByEvent(HookEvent.POST_TOOL_USE);
			List<HookRegistration> stopHooks = registry.getByEvent(HookEvent.STOP);

			// Then
			assertThat(preToolUseHooks).hasSize(2);
			assertThat(postToolUseHooks).hasSize(1);
			assertThat(stopHooks).isEmpty();
		}

	}

	@Nested
	@DisplayName("Hook Execution")
	class HookExecutionTests {

		@Test
		@DisplayName("Should execute hook callback")
		void executeCallback() {
			// Given
			AtomicBoolean called = new AtomicBoolean(false);
			String id = registry.registerPreToolUse(input -> {
				called.set(true);
				return HookOutput.allow();
			});

			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md", "/home", null, "Bash",
					"tool_123", Map.of("command", "ls"));

			// When
			HookOutput output = registry.executeHook(id, input);

			// Then
			assertThat(called.get()).isTrue();
			assertThat(output.continueExecution()).isTrue();
		}

		@Test
		@DisplayName("Should return null for non-existent hook")
		void executeNonExistentHook() {
			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md", "/home", null, "Bash",
					"tool_123", Map.of());

			HookOutput output = registry.executeHook("non_existent", input);

			assertThat(output).isNull();
		}

		@Test
		@DisplayName("Should handle callback exception gracefully")
		void handleCallbackException() {
			// Given
			String id = registry.registerPreToolUse(input -> {
				throw new RuntimeException("Test error");
			});

			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md", "/home", null, "Bash",
					"tool_123", Map.of());

			// When
			HookOutput output = registry.executeHook(id, input);

			// Then - should return block with error message
			assertThat(output.continueExecution()).isFalse();
			assertThat(output.reason()).contains("Hook execution failed");
		}

		@Test
		@DisplayName("Should pass input to callback")
		void passInputToCallback() {
			// Given
			AtomicReference<HookInput> capturedInput = new AtomicReference<>();
			String id = registry.registerPreToolUse(input -> {
				capturedInput.set(input);
				return HookOutput.allow();
			});

			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md", "/home/user",
					"default", "Write", "tool_123", Map.of("path", "/tmp/file.txt"));

			// When
			registry.executeHook(id, input);

			// Then
			HookInput.PreToolUseInput captured = (HookInput.PreToolUseInput) capturedInput.get();
			assertThat(captured.toolName()).isEqualTo("Write");
			assertThat(captured.cwd()).isEqualTo("/home/user");
			assertThat(captured.getArgument("path", String.class)).contains("/tmp/file.txt");
		}

	}

	@Nested
	@DisplayName("Hook Configuration Building")
	class HookConfigBuildingTests {

		@Test
		@DisplayName("Should build empty config when no hooks")
		void buildEmptyConfig() {
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			assertThat(config).isEmpty();
		}

		@Test
		@DisplayName("Should build config with PreToolUse hooks")
		void buildPreToolUseConfig() {
			// Given
			registry.registerPreToolUse("Bash|Write", input -> HookOutput.allow());

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then
			assertThat(config).containsKey("PreToolUse");
			List<ControlRequest.HookMatcherConfig> matchers = config.get("PreToolUse");
			assertThat(matchers).hasSize(1);
			assertThat(matchers.get(0).matcher()).isEqualTo("Bash|Write");
			assertThat(matchers.get(0).hookCallbackIds()).hasSize(1);
		}

		@Test
		@DisplayName("Should group hooks by pattern")
		void groupHooksByPattern() {
			// Given - two hooks with same pattern
			registry.register(HookRegistration.preToolUse("hook_1", "Bash", input -> HookOutput.allow()));
			registry.register(HookRegistration.preToolUse("hook_2", "Bash", input -> HookOutput.allow()));

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then - should be grouped into one matcher
			List<ControlRequest.HookMatcherConfig> matchers = config.get("PreToolUse");
			assertThat(matchers).hasSize(1);
			assertThat(matchers.get(0).hookCallbackIds()).containsExactlyInAnyOrder("hook_1", "hook_2");
		}

		@Test
		@DisplayName("Should separate hooks with different patterns")
		void separateHooksByPattern() {
			// Given - hooks with different patterns
			registry.register(HookRegistration.preToolUse("hook_bash", "Bash", input -> HookOutput.allow()));
			registry.register(HookRegistration.preToolUse("hook_write", "Write", input -> HookOutput.allow()));

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then - should be separate matchers
			List<ControlRequest.HookMatcherConfig> matchers = config.get("PreToolUse");
			assertThat(matchers).hasSize(2);
		}

		@Test
		@DisplayName("Should use wildcard pattern for all-tools hooks")
		void wildcardPatternForAllTools() {
			// Given
			registry.registerPreToolUse(input -> HookOutput.allow());

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then
			assertThat(config.get("PreToolUse").get(0).matcher()).isEqualTo(".*");
		}

		@Test
		@DisplayName("Should use max timeout from grouped hooks")
		void maxTimeoutFromGroup() {
			// Given
			registry.register(HookRegistration.builder()
				.id("hook_1")
				.event(HookEvent.PRE_TOOL_USE)
				.toolPattern("Bash")
				.callback(input -> HookOutput.allow())
				.timeout(30)
				.build());

			registry.register(HookRegistration.builder()
				.id("hook_2")
				.event(HookEvent.PRE_TOOL_USE)
				.toolPattern("Bash")
				.callback(input -> HookOutput.allow())
				.timeout(120)
				.build());

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then
			assertThat(config.get("PreToolUse").get(0).timeout()).isEqualTo(120);
		}

		@Test
		@DisplayName("Should build multi-event config")
		void buildMultiEventConfig() {
			// Given
			registry.registerPreToolUse(input -> HookOutput.allow());
			registry.registerPostToolUse(input -> HookOutput.allow());
			registry.registerUserPromptSubmit(input -> HookOutput.allow());

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then
			assertThat(config).containsKeys("PreToolUse", "PostToolUse", "UserPromptSubmit");
		}

	}

	/**
	 * The response to a {@code hook_callback} must be the CLI's hook JSON output format:
	 * {@code hookSpecificOutput} nested with camelCase keys, unset fields absent. The CLI
	 * rejects {@code "continue": null} and ignores unknown keys, and either one silently
	 * drops the hook's decision.
	 */
	@Nested
	@DisplayName("Hook Callback Response")
	class HookCallbackResponseTests {

		private static final String DENY_JSON = """
				{"hookSpecificOutput": {
				  "hookEventName": "PreToolUse",
				  "permissionDecision": "deny",
				  "permissionDecisionReason": "branch changes are blocked"}}
				""";

		private final ObjectMapper mapper = new ObjectMapper();

		private final HookInput preToolUseInput = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md",
				"/home", null, "Bash", "tool_123", Map.of("command", "git switch -c probe"));

		@Test
		@DisplayName("A PreToolUse deny is sent nested, camelCase, with no continue")
		void preToolUseDeny() throws Exception {
			String id = registry.registerPreToolUse("Bash",
					input -> HookOutput.builder()
						.hookSpecificOutput(HookOutput.HookSpecificOutput.preToolUseDeny("branch changes are blocked"))
						.build());

			JsonNode wire = wire(registry.handleCallback("req_1", id, preToolUseInput));

			assertThat(wire.at("/response/subtype").asText()).isEqualTo("success");
			assertThat(wire.at("/response/request_id").asText()).isEqualTo("req_1");
			assertThat(wire.at("/response/response")).isEqualTo(mapper.readTree(DENY_JSON));
		}

		@Test
		@DisplayName("A PreToolUse allow is sent with its decision and reason")
		void preToolUseAllow() throws Exception {
			String id = registry.registerPreToolUse("Bash",
					input -> HookOutput.builder()
						.continueExecution(true)
						.hookSpecificOutput(HookOutput.HookSpecificOutput.preToolUseAllow("read-only command"))
						.build());

			assertThat(sent(id)).isEqualTo(mapper.readTree("""
					{"continue": true,
					 "hookSpecificOutput": {
					   "hookEventName": "PreToolUse",
					   "permissionDecision": "allow",
					   "permissionDecisionReason": "read-only command"}}
					"""));
		}

		@Test
		@DisplayName("A PreToolUse updatedInput is sent nested as updatedInput")
		void preToolUseModify() throws Exception {
			String id = registry.registerPreToolUse("Bash",
					input -> HookOutput.builder()
						.hookSpecificOutput(
								HookOutput.HookSpecificOutput.preToolUseModify(Map.of("command", "git status")))
						.build());

			assertThat(sent(id)).isEqualTo(mapper.readTree("""
					{"hookSpecificOutput": {
					  "hookEventName": "PreToolUse",
					  "updatedInput": {"command": "git status"}}}
					"""));
		}

		@Test
		@DisplayName("PostToolUse and UserPromptSubmit additionalContext are sent nested")
		void additionalContext() throws Exception {
			String post = registry.registerPostToolUse(input -> HookOutput.builder()
				.hookSpecificOutput(HookOutput.HookSpecificOutput.postToolUse("the build is red"))
				.build());
			String prompt = registry.registerUserPromptSubmit(input -> HookOutput.builder()
				.hookSpecificOutput(HookOutput.HookSpecificOutput.userPromptSubmit("today is release day"))
				.build());

			assertThat(sent(post)).isEqualTo(mapper.readTree("""
					{"hookSpecificOutput": {
					  "hookEventName": "PostToolUse",
					  "additionalContext": "the build is red"}}
					"""));
			assertThat(sent(prompt)).isEqualTo(mapper.readTree("""
					{"hookSpecificOutput": {
					  "hookEventName": "UserPromptSubmit",
					  "additionalContext": "today is release day"}}
					"""));
		}

		@Test
		@DisplayName("The top-level control fields keep their names")
		void topLevelFields() throws Exception {
			String id = registry.registerPreToolUse("Bash",
					input -> HookOutput.builder()
						.continueExecution(false)
						.stopReason("policy stop")
						.suppressOutput(true)
						.systemMessage("stopped by policy")
						.decision("block")
						.reason("not allowed")
						.build());

			assertThat(sent(id)).isEqualTo(mapper.readTree("""
					{"continue": false, "suppressOutput": true, "stopReason": "policy stop",
					 "decision": "block", "systemMessage": "stopped by policy", "reason": "not allowed"}
					"""));
		}

		@Test
		@DisplayName("A missing hookEventName is filled in from the registration")
		void missingEventName() throws Exception {
			String id = registry.registerPreToolUse("Bash",
					input -> HookOutput.builder()
						.hookSpecificOutput(HookOutput.HookSpecificOutput.builder()
							.permissionDecision("deny")
							.permissionDecisionReason("branch changes are blocked")
							.build())
						.build());

			assertThat(sent(id)).isEqualTo(mapper.readTree(DENY_JSON));
		}

		@Test
		@DisplayName("A hookEventName for another event is sent as the hook returned it")
		void mismatchedEventName() throws Exception {
			String id = registry.registerPostToolUse(input -> HookOutput.builder()
				.hookSpecificOutput(HookOutput.HookSpecificOutput.preToolUseDeny("wrong event"))
				.build());

			assertThat(sent(id).at("/hookSpecificOutput/hookEventName").asText()).isEqualTo("PreToolUse");
		}

		@Test
		@DisplayName("An unknown callback ID is answered with an error")
		void unknownCallback() throws Exception {
			JsonNode wire = wire(registry.handleCallback("req_7", "hook_missing", preToolUseInput));

			assertThat(wire.at("/response/subtype").asText()).isEqualTo("error");
			assertThat(wire.at("/response/request_id").asText()).isEqualTo("req_7");
			assertThat(wire.at("/response/error").asText()).contains("hook_missing");
		}

		/**
		 * The hook output {@code handleCallback} sends for the hook registered under
		 * {@code id}.
		 */
		private JsonNode sent(String id) throws Exception {
			return wire(registry.handleCallback("req", id, preToolUseInput)).at("/response/response");
		}

		private JsonNode wire(ControlResponse response) throws Exception {
			return mapper.readTree(mapper.writeValueAsString(response));
		}

	}

	@Nested
	@DisplayName("Initialize Request Creation")
	class InitializeRequestTests {

		@Test
		@DisplayName("Should create initialize request")
		void createInitializeRequest() {
			// Given
			registry.registerPreToolUse("Bash", input -> HookOutput.allow());

			// When
			ControlRequest request = registry.createInitializeRequest("req_init_1");

			// Then
			assertThat(request.type()).isEqualTo("control_request");
			assertThat(request.requestId()).isEqualTo("req_init_1");
			assertThat(request.request()).isInstanceOf(ControlRequest.InitializeRequest.class);

			var initRequest = (ControlRequest.InitializeRequest) request.request();
			assertThat(initRequest.hooks()).containsKey("PreToolUse");
		}

	}

	@Nested
	@DisplayName("Pattern Matching")
	class PatternMatchingTests {

		@Test
		@DisplayName("Should match exact tool name")
		void matchExactToolName() {
			HookRegistration reg = HookRegistration.preToolUse("test", "Bash", input -> HookOutput.allow());

			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Write")).isFalse();
		}

		@Test
		@DisplayName("Should match pattern with OR")
		void matchPatternWithOr() {
			HookRegistration reg = HookRegistration.preToolUse("test", "Bash|Write|Read", input -> HookOutput.allow());

			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Write")).isTrue();
			assertThat(reg.matchesTool("Read")).isTrue();
			assertThat(reg.matchesTool("Edit")).isFalse();
		}

		@Test
		@DisplayName("Should match wildcard pattern")
		void matchWildcardPattern() {
			HookRegistration reg = HookRegistration.preToolUse("test", ".*", input -> HookOutput.allow());

			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Write")).isTrue();
			assertThat(reg.matchesTool("AnyTool")).isTrue();
		}

		@Test
		@DisplayName("Null pattern should match all tools")
		void nullPatternMatchesAll() {
			HookRegistration reg = HookRegistration.preToolUse("test", input -> HookOutput.allow());

			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Write")).isTrue();
			// Null pattern matches everything including null toolName
			assertThat(reg.matchesTool(null)).isTrue();
		}

	}

	@Nested
	@DisplayName("HookCallback Static Methods")
	class HookCallbackStaticMethodsTests {

		@Test
		@DisplayName("HookCallback.allow() should always return allow")
		void allowCallback() {
			HookCallback callback = HookCallback.allow();
			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp", "/home", null, "Bash",
					"tool_123", Map.of());

			HookOutput output = callback.handle(input);

			assertThat(output.continueExecution()).isTrue();
		}

		@Test
		@DisplayName("HookCallback.block() should always return block")
		void blockCallback() {
			HookCallback callback = HookCallback.block("Not allowed");
			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp", "/home", null, "Bash",
					"tool_123", Map.of());

			HookOutput output = callback.handle(input);

			assertThat(output.continueExecution()).isFalse();
			assertThat(output.reason()).isEqualTo("Not allowed");
		}

	}

}
