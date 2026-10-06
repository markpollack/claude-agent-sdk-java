/*
 * Copyright 2024 Mark Pollack
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

package io.github.markpollack.claude.agent.sdk.config;

/**
 * Permission modes for Claude Code tool usage. Corresponds to PermissionMode in Python
 * SDK.
 *
 * <p>
 * Every constant for which {@link #isPermissionModeValue()} holds is passed to the CLI as
 * {@code --permission-mode <value>}. {@code CLIFlagParityIT} checks that the CLI accepts
 * each of them.
 * </p>
 */
public enum PermissionMode {

	/**
	 * Default permission mode - prompt for tool usage permissions.
	 *
	 * <p>
	 * CLI 2.1.291 no longer lists {@code default} among the {@code --permission-mode}
	 * choices but still accepts it. {@link #MANUAL} is its new name.
	 * </p>
	 */
	DEFAULT("default"),

	/**
	 * Automatically accept edit permissions without prompting.
	 */
	ACCEPT_EDITS("acceptEdits"),

	/**
	 * Bypass all permission checks (use with caution).
	 */
	BYPASS_PERMISSIONS("bypassPermissions"),

	/**
	 * Dangerously skip all permission checks. Recommended only for sandboxes with no
	 * internet access. Sent as {@code --dangerously-skip-permissions}, not as a
	 * {@code --permission-mode} value.
	 */
	DANGEROUSLY_SKIP_PERMISSIONS("dangerously-skip-permissions"),

	/**
	 * Prompt for tool usage permissions; the name newer CLIs list for {@link #DEFAULT}.
	 * The session's init message reports it as {@code default}.
	 */
	MANUAL("manual"),

	/**
	 * Let the CLI decide tool permissions automatically.
	 *
	 * <p>
	 * Not every model supports it. On one that does not, the CLI silently falls back to
	 * {@code default}: the session's init message then reports
	 * {@code permissionMode: "default"} rather than failing (observed with Haiku on CLI
	 * 2.1.291).
	 * </p>
	 */
	AUTO("auto"),

	/**
	 * Deny any tool use that is not pre-approved, instead of prompting.
	 */
	DONT_ASK("dontAsk"),

	/**
	 * Plan mode - Claude can analyze but not modify files or run commands.
	 */
	PLAN("plan");

	private final String value;

	PermissionMode(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	/**
	 * Whether this mode is passed as {@code --permission-mode <value>}. Only
	 * {@link #DANGEROUSLY_SKIP_PERMISSIONS} is not: it is a flag of its own.
	 */
	public boolean isPermissionModeValue() {
		return this != DANGEROUSLY_SKIP_PERMISSIONS;
	}

	/**
	 * Creates PermissionMode from string value.
	 */
	public static PermissionMode fromValue(String value) {
		for (PermissionMode mode : values()) {
			if (mode.value.equals(value)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unknown permission mode: " + value);
	}

	@Override
	public String toString() {
		return value;
	}

}