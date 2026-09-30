# Jackson 3 Migration — Design

- **Date:** 2026-09-30
- **Branch:** `jackson3`
- **Target version:** `2.0.0` (development version `2.0.0-SNAPSHOT`)

## Goal

Move `claude-code-sdk` completely off Jackson 2 (`com.fasterxml.jackson.core:jackson-core` /
`jackson-databind`) and onto Jackson 3 (`tools.jackson`).

Today every consumer receives two JSON stacks: Jackson 2 for the SDK's own parsing and Jackson
3 because `mcp` 2.0.1 requires it. Two stacks means two CVE floors to police and two sets of
jars on every classpath. After this change there is one.

"No Jackson 2" means no `com.fasterxml.jackson.core:jackson-core` or `jackson-databind`.
`com.fasterxml.jackson.core:jackson-annotations` **stays**: Jackson 3 deliberately reuses the
2.x annotations artifact and the `com.fasterxml.jackson.annotation` package (the
`tools.jackson` 3.2.2 BOM pins it at `2.22`).

## Decisions

| Question | Decision |
|---|---|
| Public API exposes Jackson 2 types | Swap to `tools.jackson` equivalents in place; signal the break with a major version bump to **2.0.0**. No Jackson 2 overloads are kept. |
| Mapper defaults | Adopt **Jackson 3 defaults** as-is. Every 2→3 default change is catalogued in Appendix A for later review. |
| Approach | **In-place swap**: each class keeps its own mapper; imports, types, and method names are renamed mechanically. No shared-mapper class, no OpenRewrite. |

## Non-goals

- No shared/central mapper. (Revisit if the Appendix A review leads to overriding any default —
  a single knob then earns its keep.)
- No change to wire behavior beyond what Appendix A documents.
- No `release-notes/2.0.0.md` now; release notes are written after the release. The
  [Breaking changes](#breaking-changes-release-notes-input) section is its input.
- Historical release notes (`release-notes/1.5.0.md`) are not edited.

## 1. Build and dependencies

**Parent `pom.xml`**
- Remove the `jackson.version` property and the `com.fasterxml.jackson:jackson-bom` import.
- Keep the `tools.jackson:jackson-bom` import (`jackson3.version` = 3.2.2); it manages
  `jackson-annotations` too.
- Bump `<version>` to `2.0.0-SNAPSHOT`.

**`claude-code-sdk/pom.xml`**
- Update the parent reference to `2.0.0-SNAPSHOT`.
- Remove `com.fasterxml.jackson.core:jackson-core` and `jackson-databind`.
- Keep `com.fasterxml.jackson.core:jackson-annotations` (the SDK's source uses the
  annotations directly; version now comes from the Jackson 3 BOM).
- Keep the direct `tools.jackson.core:jackson-core`, `jackson-databind`, and
  `tools.jackson.dataformat:jackson-dataformat-yaml` declarations. Rewrite their comment
  block: the SDK now imports `tools.jackson` itself, and the declarations still exist so the
  version floor survives POM flattening.

**`scripts/standalone-consumer-gate.sh`**
- Remove `JACKSON2_FLOOR` and the 2.x floor branch.
- Any `jackson-core-2.*` or `jackson-databind-2.*` jar in the resolved consumer closure is a
  **fail** ("Jackson 2 must not reach consumers"). `jackson-annotations-2.*` is allowed.
- Keep the Jackson 3 floor check and the single-minor alignment check.
- Rename the smoke's "Jackson 2 path" comment to "SDK parsing path"; update the section-7
  header comment accordingly.

**Docs**
- Update the Jackson-floor wording in `.github/workflows/ci.yml` (gate job comment) and
  `RELEASING.md` so it no longer implies a Jackson 2 floor.

## 2. Code changes

Scope: 28 main-source files import Jackson, but 20 of them use only annotations (unchanged).
The 8 that change are `DefaultClaudeSyncClient`, `DefaultClaudeAsyncClient`, `ResultMessage`,
`StreamingTransport`, `McpMessageHandler`, `MessageParser`, `ControlMessageParser`, and
`JsonResultParser`. There are also 3 test files. Separately, 5 annotation-only files get a
one-attribute `@JsonTypeInfo` change: `Message`, `ContentBlock`, `HookInput`, `ControlResponse`,
and `McpServerConfig` (see Polymorphic type ids).

### Mechanical renames

| Jackson 2 | Jackson 3 |
|---|---|
| `com.fasterxml.jackson.databind.ObjectMapper` / `JsonNode` / `DeserializationFeature` | `tools.jackson.databind.ObjectMapper` / `JsonNode` / `DeserializationFeature` |
| `com.fasterxml.jackson.core.type.TypeReference` | `tools.jackson.core.type.TypeReference` |
| `com.fasterxml.jackson.core.JsonProcessingException` | `tools.jackson.core.JacksonException` (unchecked) |
| `node.isTextual()` / `node.asText()` | `node.isString()` / `node.asString()` |
| `node.fields().forEachRemaining(...)` | `node.properties().forEach(...)` (`fields()` no longer exists) |
| `mapper.copy().configure(F, false)` | `mapper.rebuild().disable(F).build()` |
| `com.fasterxml.jackson.annotation.*` | **unchanged** |

### Public API changes

Same names, same parameter order; only the Jackson type's package changes.

| Member | Before | After |
|---|---|---|
| `ResultMessage.getStructuredOutputAs(Class<T>, ObjectMapper)` | `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` |
| `McpMessageHandler(ObjectMapper)` | same | same |
| `ControlMessageParser(ObjectMapper)` | same | same |
| `ControlMessageParser(ObjectMapper, int)` | same | same |
| `ControlMessageParser.parseFromNode(JsonNode, String)` | `com.fasterxml.jackson.databind.JsonNode` | `tools.jackson.databind.JsonNode` |
| `MessageParser.parseMessageFromNode(JsonNode)` | same | same |

### Error handling

Jackson 3 silently changes two contracts the SDK relies on. Both are preserved.

1. **Parsers keep throwing `MessageParseException`.**
   - Every `catch (JsonProcessingException e)` becomes `catch (JacksonException e)`.
     `JacksonException` also covers `JsonNodeException`, which Jackson 3 accessors now throw
     for values they cannot coerce (see Appendix A, JsonNode rows).
   - The public node-level entry points `MessageParser.parseMessageFromNode(JsonNode)` and
     `ControlMessageParser.parseFromNode(JsonNode, String)` also map `JacksonException` to
     `MessageParseException`, so a direct caller never sees a raw Jackson exception.
   - `isControlRequest` / `extractRequestId` keep returning `false` / `null` on any
     `JacksonException`.
2. **`StreamingTransport` keeps its serialization-failure behavior.** Three
   `catch (IOException e)` blocks around `writeValueAsString` only worked because Jackson 2's
   `JsonProcessingException extends IOException`; Jackson 3's `JacksonException` does not.
   - `sendUserMessage` and `sendResponse`: the compiler flags the now-dead `IOException`
     catch. Replace with `catch (JacksonException e)`, still wrapping in `TransportException`.
   - MCP config temp-file block: `Files` calls still throw `IOException`, so the compiler does
     **not** flag it. Change to `catch (IOException | JacksonException e)` so a serialization
     failure still logs and skips `--mcp-config` instead of aborting startup.
   - `--json-schema` block: `catch (JsonProcessingException e)` becomes
     `catch (JacksonException e)`; still logs and skips the flag.

### Polymorphic type ids

*(Amended 2026-09-30 after probing found this during planning.)*

Five `@JsonTypeInfo` hierarchies declare their type-id property with `As.PROPERTY`, while their
subtypes also expose a property of the same name. Jackson 2 serialized them with a duplicate key
(e.g. `{"subtype":"success","subtype":"success",...}`). Jackson 3 refuses and throws
`InvalidDefinitionException: Conflict between type id property ... and bean property with same
name` at serialization time. Two of these hierarchies are on the SDK's own wire path:

| Hierarchy | Type-id property | SDK serializes it? |
|---|---|---|
| `ControlResponse.ResponsePayload` | `subtype` | **Yes**: `StreamingTransport.sendResponse`. Without a fix, every permission, hook, and MCP control response fails. |
| `McpServerConfig` | `type` | **Yes**: `--mcp-config` temp file. Without a fix, every external MCP server silently disappears (the log-and-skip catch hides it). |
| `HookInput` | `hook_event_name` | No (deserialized only); consumers who serialize it break. |
| `Message` | `type` | No; consumers who serialize it break. |
| `ContentBlock` | `type` | No; consumers who serialize it break. |

`ControlRequest.ControlRequestPayload` has no conflict and is unchanged.

**Fix:** change `include = JsonTypeInfo.As.PROPERTY` to `include = JsonTypeInfo.As.EXISTING_PROPERTY`
on those five declarations (for `McpServerConfig`, add the attribute; it currently relies on the
`PROPERTY` default). Leave every other attribute unchanged (`HookInput` and `McpServerConfig`
keep `visible = true`; `McpServerConfig` keeps `defaultImpl`). A probe using Jackson 3 mix-ins
confirmed that all five serialize with a single key and deserialize exactly as they do under
Jackson 2.

With `EXISTING_PROPERTY` the key's value comes from the record's own component or accessor
instead of the class. The SDK always populates it (`ControlResponse.success/error`, the
`McpServerConfig` convenience constructors). A caller who builds one of these records through
its canonical constructor with a `null` type gets no type key; Jackson 2 still wrote the
class-derived id.

### Defaults

All mappers use Jackson 3 defaults. `ControlMessageParser` keeps explicitly disabling
`FAIL_ON_UNKNOWN_PROPERTIES` on caller-supplied mappers (via `rebuild()`), because a caller may
have enabled it. The default-constructed mapper needs no configuration, since Jackson 3 already
defaults that feature to off.

## 3. Testing and verification

1. **Migrate test code**: `NoPromptConnectRegressionTest`, `McpServerConfigTest`,
   `HookIntegrationIT` — same renames as main.
2. **New tests, written first (TDD):**
   - **Parser error contract.** A wrong-typed field (e.g. `"num_turns": {}` on a result line,
     `"type": {}`) must surface as `MessageParseException` from
     `ControlMessageParser.parse`, `MessageParser.parseMessage`,
     `JsonResultParser.parseJsonResult`, `MessageParser.parseMessageFromNode`, and
     `ControlMessageParser.parseFromNode`. A naive rename fails the `JsonResultParser` and
     node-level cases with `JsonNodeException`.
   - **`--json-schema` serialization failure.** A schema value whose getter throws must yield a
     command without `--json-schema` and no exception, exercised through
     `StreamingTransport.buildStreamingCommand` (the seam `CLIFlagParityTest` uses).
   - **MCP temp-file multi-catch:** not unit-tested. Once the type-id fix is in, the external
     configs are records of strings and string maps and cannot realistically fail to
     serialize; the catch is defensive and covered by review.
   - **Polymorphic serialization** (all fail under a naive rename with
     `InvalidDefinitionException`):
     - `ControlResponse.success(...)` / `ControlResponse.error(...)` serialize with exactly one
       `subtype` key carrying `"success"` / `"error"`.
     - `HookInput`, `Message`, `ContentBlock`, and `McpServerConfig` serialize through their
       base type with a single type key, and read back to the same subtype. The existing
       `McpServerConfigTest` already covers `McpServerConfig`.
     - `--mcp-config` content: `buildStreamingCommand` with a stdio and an http server writes a
       temp file whose JSON tree equals the expected `{"mcpServers":{...}}`. This is the guard
       the log-and-skip catch would otherwise defeat.
3. **Wire regression.** `WireFixtureTest` (real CLI 2.1.162 stdout lines) and the existing unit
   suite pass unchanged. This is the evidence that Jackson 3 defaults do not change how real
   traffic parses. A test failing only on JSON property order is fixed by comparing JSON trees,
   not by pinning the new order.
4. **Verification commands** (all credential-free):
   - `./mvnw verify`
   - `./scripts/standalone-consumer-gate.sh`
   - `./mvnw -pl claude-code-sdk dependency:tree` shows no
     `com.fasterxml.jackson.core:jackson-core` / `jackson-databind`
   - `grep -rE 'com\.fasterxml\.jackson\.(core|databind)' --include='*.java' claude-code-sdk/src`
     returns nothing
   - The paid live suite (`-Dfailsafe.excluded.groups=`) runs at release time per
     `RELEASING.md`, not as part of this change.

## Success criteria

- No `com.fasterxml.jackson.core:jackson-core` or `jackson-databind` in the SDK's source,
  POMs, or consumer runtime closure, enforced by the standalone consumer gate.
- `./mvnw verify` and the standalone consumer gate pass.
- Parser and transport error contracts unchanged (`MessageParseException`,
  `TransportException`, log-and-skip for optional CLI flags).
- Appendix A delivered for the maintainer's later review of Jackson 3 defaults.

## Breaking changes (release-notes input)

- **Jackson 3.** The SDK now uses Jackson 3 (`tools.jackson`) exclusively; Jackson 2
  `jackson-core` / `jackson-databind` are no longer dependencies. `jackson-annotations` 2.x
  remains, as Jackson 3 requires.
- **Public signatures now take Jackson 3 types:**
  `ResultMessage.getStructuredOutputAs(Class, ObjectMapper)`,
  `McpMessageHandler(ObjectMapper)`, `ControlMessageParser(ObjectMapper)`,
  `ControlMessageParser(ObjectMapper, int)`, `ControlMessageParser.parseFromNode(JsonNode, String)`,
  `MessageParser.parseMessageFromNode(JsonNode)`. Callers change imports from
  `com.fasterxml.jackson.databind` to `tools.jackson.databind`.
- **Stricter parsing** (from Jackson 3 defaults): wrong-typed or out-of-range fields in parsed
  messages, explicit JSON `null` for the four primitive fields listed in Appendix A, and
  lines with trailing content after the JSON value now fail with `MessageParseException`
  rather than being silently defaulted or truncated.
- **Exception type change.** `ResultMessage.getStructuredOutputAs` conversion failures now throw
  `JacksonException` (unchecked) instead of `IllegalArgumentException`; an existing
  `catch (IllegalArgumentException e)` still compiles but no longer catches them.
- **No more duplicate type keys.** Serialized `ControlResponse` payloads, `McpServerConfig`,
  `HookInput`, `Message`, and `ContentBlock` now carry their type key (`subtype`, `type`,
  `hook_event_name`) once instead of twice. Its value comes from the record itself, so a record
  built with a `null` type now has no type key.

## Appendix A — Jackson 2 → 3 default and behavior changes

Compared: Jackson **2.22.2** (current) vs **3.2.2** (target).

Sources:
- [JSTEP-2](https://github.com/FasterXML/jackson-future-ideas/wiki/JSTEP-2) (default changes)
- [JSTEP-3](https://github.com/FasterXML/jackson-future-ideas/wiki/JSTEP-3) (`JsonNode`)
- [Migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)
- [3.1](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1) and [3.2](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.2) release notes

Rows marked *probed* were confirmed by running both jar versions side by side in `jshell` on
2026-09-30.

**Impact key:** **None** — no effect on this SDK. **Consumer** — no wire effect; affects only
consumers who serialize SDK types with their own mapper. **Change** — observable SDK behavior
change, accepted and handled. **Risk** — could bite if the CLI's output changes; watch it.

### Streaming (jackson-core)

| Setting | 2.x | 3.x | SDK impact |
|---|---|---|---|
| `StreamReadConstraints` max nesting depth *(probed)* | 1000 | 500 | **Risk (low).** A CLI line nested deeper than 500 levels now fails as `MessageParseException`. Real tool inputs/results are far shallower. |
| `StreamWriteConstraints` max nesting depth *(probed)* | 1000 | 500 | **Risk (low).** Writing a >500-deep structure (user JSON schema, MCP tool result) fails. |
| `StreamReadConstraints` max string length *(probed)* | 20,000,000 | 100,000,000 | **None.** Looser; the SDK already rejects lines above `maxBufferSize` (1 MB default) before Jackson sees them. |
| Other read constraints: number length 1000, name length 50000, document length and token count unlimited *(probed)* | — | same | **None.** |
| `StreamReadFeature.USE_FAST_DOUBLE_PARSER` | off | on | **None.** Same values, faster. |
| `StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER` | off | on | **None.** |
| `TokenStreamFactory.Feature.INTERN_PROPERTY_NAMES` | on | off | **None.** Memory/performance only. |
| `JsonWriteFeature.ESCAPE_FORWARD_SLASHES` | off | off (planned change reverted in 3.0.0-rc7) | **None.** |
| `JsonReadFeature` / `JsonWriteFeature` (all others) | — | unchanged | **None.** |

### `MapperFeature`

| Setting | 2.x | 3.x | SDK impact |
|---|---|---|---|
| `SORT_PROPERTIES_ALPHABETICALLY` | off | on | **None on the wire.** Serialized POJO key order may change; JSON key order is not semantic to the CLI. Records keep declaration order for creator properties (`SORT_CREATOR_PROPERTIES_FIRST` stays on). Tests pinning exact JSON strings compare trees instead. |
| `SORT_CREATOR_PROPERTIES_BY_DECLARATION_ORDER` | off | removed; behaves as on | **None.** See above. |
| `DETECT_PARAMETER_NAMES` (parameter-names module built in) | module not registered | on | **None.** Every record component carries an explicit `@JsonProperty`. |
| `ALLOW_FINAL_FIELDS_AS_MUTATORS` | on | off | **None.** SDK types are records bound through constructors. |
| `USE_GETTERS_AS_SETTERS` | on | off | **None.** Records bound through constructors. |
| `DEFAULT_VIEW_INCLUSION` | on | off | **None.** No `@JsonView` in the SDK. |
| `FIX_FIELD_NAME_UPPER_CASE_PREFIX` | off | on | **None.** All wire names are explicit via `@JsonProperty`. |
| `USE_STD_BEAN_NAMING` | off | removed; behaves as on | **None.** Explicit names. |
| `AUTO_DETECT_CREATORS/FIELDS/GETTERS/IS_GETTERS/SETTERS` | features | removed (use `changeDefaultVisibility`) | **None.** Not configured. |
| `EXTERNAL_TYPE_ID_ALWAYS_VISIBLE` (3.2) | behaves as on | off | **None.** No polymorphic type uses `EXTERNAL_PROPERTY`. |
| `OVERRIDE_PUBLIC_ACCESS_MODIFIERS` | on | on (may flip in a later 3.x) | **None today.** |

### `DeserializationFeature` / `EnumFeature`

| Setting | 2.x | 3.x | SDK impact |
|---|---|---|---|
| `FAIL_ON_UNKNOWN_PROPERTIES` | on | off | **None.** Every typed read path already ignored unknown properties (`ControlMessageParser` disables it; `HookInput` records use `ignoreUnknown`). |
| `FAIL_ON_NULL_FOR_PRIMITIVES` (3.2: explicit `null` only; absent fields still default) | off | on | **Risk.** Explicit JSON `null` now fails binding for the four primitive components reached through Jackson binding: `RateLimitInfo.resetsAt` (`long`), `RateLimitInfo.isUsingOverage` (`boolean`), and `HookInput.StopInput` / `SubagentStopInput.stop_hook_active` (`boolean`). The `RateLimitInfo` fields fail as `MessageParseException`. `HookInput` is never bound by a parser, only in `handleHookCallback` via `convertValue` (`DefaultClaudeSyncClient.java:491`, `DefaultClaudeAsyncClient.java:669`), so an explicit `"stop_hook_active": null` raises `JacksonException`, which the client's `catch (Exception)` turns into `ControlResponse.error("Hook execution failed: ...")`: the user's hook does not run and the CLI receives an error response. `ResultMessage`/`Usage`/`Cost`/`Metadata` are built manually from nodes, not bound, so they are unaffected by this row. |
| `FAIL_ON_TRAILING_TOKENS` *(probed)* | off | on | **Change.** Applies to `readTree` as well. A line carrying content after its first JSON value (e.g. two concatenated objects) now fails with `MessageParseException`; 2.x silently parsed the first value. The CLI emits one object per line. In `RobustStreamParser` (`RobustStreamParser.java:149`), the `readTree` failure carries `rawInput`, which it treats as "incomplete, keep accumulating", so a line with trailing content poisons its buffer until the size limit (2.x only dropped the trailing value). `RobustStreamingProcessor` is not used by either client. |
| `EnumFeature.READ_ENUMS_USING_TO_STRING` | off | on | **None.** No enum is bound by Jackson on the wire; wire enums are strings. |
| `FAIL_ON_UNEXPECTED_VIEW_PROPERTIES` | off | off (planned change reverted) | **None.** |

### `SerializationFeature` / `EnumFeature` / `DateTimeFeature`

| Setting | 2.x | 3.x | SDK impact |
|---|---|---|---|
| `EnumFeature.WRITE_ENUMS_USING_TO_STRING` | off | on | **Consumer.** The SDK serializes no enums on the wire. A consumer serializing `PermissionMode` gets its CLI value (e.g. `acceptEdits`) instead of the constant name, because it overrides `toString()`. `ResultStatus`, `HookEvent`, and `OutputFormat` do not override it and are unchanged. |
| `FAIL_ON_EMPTY_BEANS` | on | off | **None.** The SDK serializes no property-less beans. |
| `FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` | on | off | **None.** `ORDER_MAP_ENTRIES_BY_KEYS` not enabled. |
| `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` | on | off (ISO-8601 strings) | **None.** No date/time values on the wire. |
| `DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS` | on | off (ISO-8601 strings) | **Consumer.** `java.time` support is now built in: a consumer serializing `Metadata` gets `duration` / `apiDuration` as ISO-8601 strings from its `Duration` getters, where 2.x without the JSR-310 module failed. |
| `DateTimeFeature.ONE_BASED_MONTHS` | off | on | **None.** No `Month` values. |
| UTC rendering (`WRITE_UTC_AS_OFFSET`) | `+00` offset | trailing `Z` | **None.** |
| `java.time.Month` handled as date/time (numeric) rather than enum | enum | date/time | **None.** |

### `JsonNode` (JSTEP-3)

| Behavior | 2.x | 3.x | SDK impact |
|---|---|---|---|
| `asText()` on object/array *(probed)* | `""` | throws `JsonNodeException` | **Change, handled.** Surfaces as `MessageParseException` (section 2). |
| `asInt()` / `asDouble()` on non-numeric string, object, array *(probed)* | `0` / `0.0` | throws `JsonNodeException` | **Change, handled.** `JsonResultParser` getters (which only guard `null`) now fail the message instead of defaulting to `0`. `MessageParser` guards types first and is unaffected. |
| `asInt()` / `asDouble()` on boolean *(probed)* | `1`/`0`, `1.0`/`0.0` | throws `JsonNodeException` | **Change, handled.** Same mapping. |
| `asInt()` on a long beyond `int` range *(probed)* | silent overflow (`12345678901` → `-539222987`) | throws `JsonNodeException` | **Change, handled.** `MessageParser.getIntField` / `JsonResultParser.getIntField` fail the message instead of returning garbage. |
| `asBoolean()` on non-boolean string, object, array, or float *(probed)* | `false` | throws `JsonNodeException` | **Change, handled.** Same mapping. (`asBoolean()` on an int is `true`/`false` in both.) |
| `asText()` → `asString()` on JSON `null` *(probed)* | `"null"` | `""` | **None.** Every call site checks `isNull()`/`isString()` first; `isControlRequest` compares to `"control_request"`, which neither value matches. |
| `asInt()` / `asDouble()` / `asBoolean()` on JSON `null` *(probed)* | `0` / `0.0` / `false` | same | **None.** |
| `asXxx(defaultValue)` on `NullNode` (3.1, databind#5558) | returns coerced value | returns `defaultValue` | **None.** The SDK does not use default-arg variants. |
| `fields()`, `elements()` | present | removed → `properties()`, `values()` | Handled by the rename table. |
| `asText()`, `isTextual()`, `textValue()` | present | still present; 3.x names are `asString()`, `isString()`, `stringValue()` | Handled by the rename table (3.x names used). |
| `DecimalNode` via `JsonNodeFactory` | trailing zeros stripped | preserved | **None.** Floats parse to `DoubleNode` (`USE_BIG_DECIMAL_FOR_FLOATS` off). |

### Exceptions and API shape

| Behavior | 2.x | 3.x | SDK impact |
|---|---|---|---|
| Type-id property (`@JsonTypeInfo`, `As.PROPERTY`) with the same name as a bean property *(probed)* | writes the key twice | throws `InvalidDefinitionException` at serialization | **Change, handled.** Five hierarchies switch to `As.EXISTING_PROPERTY` (section 2, Polymorphic type ids). |
| Base exception | `JsonProcessingException`, checked, `extends IOException` | `JacksonException`, unchecked, `extends RuntimeException` | **Change, handled.** Section 2 error handling; the three `IOException` catches are the non-obvious part. |
| `ObjectMapper` mutability | mutable (`configure`, `copy`) | immutable (`builder()`, `rebuild()`) | Handled by the rename table. |
| Java baseline | 8 | 17 | **None.** SDK targets Java 21. |
| JDK8 / JSR-310 / parameter-names modules | separate | built in | **Consumer** (see `WRITE_DURATIONS_AS_TIMESTAMPS`). |
