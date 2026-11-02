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

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class HookResponseWireTest {

    @TempDir
    Path directory;

    @Test
    void syncHooksRetainNativeWireAndApplyDecisions() throws Exception {
        HookWireProbe.run("sync", directory.resolve("sync"), fixture());
    }

    @Test
    void asyncHooksRetainNativeWireAndApplyDecisions() throws Exception {
        HookWireProbe.run("async", directory.resolve("async"), fixture());
    }

    private Path fixture() throws Exception {
        return Path.of(getClass().getResource("/hook-wire-child.py").toURI());
    }

}
