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

package io.github.markpollack.claude.agent.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.hooks.HookRegistry;
import io.github.markpollack.claude.agent.sdk.types.control.HookInput;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput;

/** Public-API-only probe, also compiled separately against installed runtime JARs. */
public final class HookWireProbe {

    private HookWireProbe() {
    }

    public static void main(String[] args) throws Exception {
        run(args[0], Path.of(args[1]), Path.of(args[2]));
    }

    public static void run(String mode, Path directory, Path fixture) throws Exception {
        Files.createDirectories(directory);
        Path child = directory.resolve("child.py");
        Files.copy(fixture, child);
        Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rwxr-xr-x"));
        System.out.println("SDK_JAR=" + ClaudeClient.class.getProtectionDomain().getCodeSource().getLocation());
        HookRegistry hooks = new HookRegistry();
        hooks.registerPreToolUse(".*", HookWireProbe::response);
        hooks.registerPostToolUse(".*", HookWireProbe::response);
        hooks.registerUserPromptSubmit(HookWireProbe::response);
        if (mode.equals("sync")) {
            ClaudeSyncClient client = ClaudeClient.sync().workingDirectory(directory).claudePath(child.toString())
                .timeout(Duration.ofSeconds(10)).hookRegistry(hooks).build();
            try {
                client.connect();
                awaitOutcome(directory);
            }
            finally {
                client.close();
                require(client.transportResources().orElseThrow().observedResourcesTerminated(), "sync cleanup");
            }
        }
        else {
            ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(directory).claudePath(child.toString())
                .timeout(Duration.ofSeconds(10)).hookRegistry(hooks).build();
            try {
                client.connect().block(Duration.ofSeconds(10));
                awaitOutcome(directory);
            }
            finally {
                client.close().block(Duration.ofSeconds(10));
                require(client.transportResources().orElseThrow().observedResourcesTerminated(), "async cleanup");
            }
        }
        require(Files.readString(directory.resolve("allowed-action")).equals("executed"), "allow did not apply");
        require(!Files.exists(directory.resolve("denied-action")), "deny executed");
        require(Files.readString(directory.resolve("modified-action")).equals("modified"), "updated input lost");
        System.out.println("HOOK_WIRE_PASS=" + mode + " cases=13");
    }

    private static void awaitOutcome(Path directory) throws Exception {
        Path outcome = directory.resolve("outcome.json");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(outcome) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        require(Files.exists(outcome), "fixture did not finish");
        var result = new ObjectMapper().readTree(Files.readString(outcome));
        require(result.path("passed").asBoolean(), result.toString());
        require(result.path("cases").size() == 13, "missing fixture cases");
    }

    private static HookOutput response(HookInput input) {
        if (input instanceof HookInput.PostToolUseInput) {
            return HookOutput.builder().hookSpecificOutput(HookOutput.HookSpecificOutput.postToolUse("post context")).build();
        }
        if (input instanceof HookInput.UserPromptSubmitInput) {
            return HookOutput.builder().hookSpecificOutput(HookOutput.HookSpecificOutput.userPromptSubmit("prompt context")).build();
        }
        String name = ((HookInput.PreToolUseInput) input).toolName();
        return switch (name) {
            case "allow" -> HookOutput.builder().hookSpecificOutput(HookOutput.HookSpecificOutput.preToolUseAllow()).build();
            case "deny" -> HookOutput.builder().hookSpecificOutput(HookOutput.HookSpecificOutput.preToolUseDeny("policy denied")).build();
            case "ask" -> HookOutput.builder().hookSpecificOutput(HookOutput.HookSpecificOutput.preToolUseAsk()).build();
            case "modify" -> HookOutput.builder().hookSpecificOutput(HookOutput.HookSpecificOutput.preToolUseModify(Map.of("value", "modified"))).build();
            case "empty" -> HookOutput.builder().build();
            case "controls" -> HookOutput.builder().continueExecution(false).suppressOutput(true).stopReason("stop")
                .decision("block").systemMessage("notice").reason("policy").build();
            case "failure" -> throw new IllegalStateException("deliberate callback failure");
            case "null" -> null;
            case "async" -> HookOutput.async();
            case "async-timeout" -> HookOutput.async(1234);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

}
