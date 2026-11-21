# Hermes API notes

Observed against Hermes Agent v0.22.5 (commit `a8fed44bbe`) on 2026-11-19 with
`tools/probe/record_fixtures.py`. Fixtures live in `app/src/test/resources/fixtures/`.
Source references are paths in the Hermes repository at that commit.

General rules:

- Auth: `Authorization: Bearer <API_SERVER_KEY>` on every request.
- Fixture fidelity. Every fixture has passed `sanitize()` in `tools/probe/record_fixtures.py`,
  which decodes each JSON document (and each SSE `data:` payload, joining multiline data fields)
  and re-encodes it with Python `json.dumps` defaults: the same separators and `\uXXXX` escaping
  as the server's aiohttp `json_response`, so an unchanged body comes out byte-identical (checked
  on every raw fixture). No trailing newline. SSE fixtures keep every comment, `id:` and blank line.
  - **Privacy placeholders** (the only value changes): every number under a `usage` object at any
    depth, and every token or cost counter field anywhere (keys ending in `_tokens` or `_cost_usd`,
    and `token_count`) is replaced with a fixed value: `input_tokens` 1000, `output_tokens` 100,
    `total_tokens` 1100, every other counter 0. Every `response_item_id` value (a provider item id)
    becomes `"item_placeholder"`. This affects run status, run events, messages and every session
    receipt (session rows carry `input_tokens`, `output_tokens` and so on). Never assert on these
    values; they say nothing about real usage.
  - **Model and provider identifiers**: every non-empty string value under a key `model`,
    `provider`, `requested` or `runtime` (at any depth, including `usage.runtime` and top-level
    `runtime`) becomes a fixed placeholder: `example-model` (`model`, `requested`),
    `example-provider` or `example-runtime`. The literal `hermes-agent` (the public API model
    alias) is kept. Empty strings and `null` stay as served.
  - **Format check**: a `.json` fixture must decode to a JSON object with the top-level keys its
    name expects (for example `error` with a `code` for error fixtures) and an `.sse` fixture must
    carry `data:` frames; otherwise the scenario fails and writes nothing.
  - **List rows**: `sessions_list.json` and `sessions_list_pinned_archived.json` come from a list
    request filtered by a title the script sent. The script then decodes the body, drops any row
    whose id it did not create and re-serializes it before sanitizing. On the recorded runs
    nothing was dropped.
  - The run-scenario fixtures (`run_*`, `steer_response.json`, `messages_*`, `events_*.sse`) were
    recorded by the first script version and are kept to avoid new model runs; they were passed
    through the same `sanitize()` afterwards, so they share the format above. `events_tool_run.sse`
    and `events_tool_run_resumed.sse` had HTTP chunk-size lines removed after capture (lossless).
  - There is no `model_options.json` fixture (see below).
- Errors are `{"error": {"message", "type", "param"?, "code"}}`. Branch on `code`.
- Timestamps are Unix seconds as floats. Message ids are integers; run ids are
  `run_<32 hex>`.

## Endpoints the app uses

### GET /v1/capabilities
`capabilities.json`. `features.run_unknown_resolution: true` on this server, which means
canonical mode: runs go through the session authority (admissions, `execution_generation`,
`pending_controls`). `features.reasoning_streaming: true` is advertised but no
`reasoning.available` reaches `/v1/runs` events in canonical mode (see the table).

### GET /api/model/options
No fixture in this task: the body describes the server's provider accounts, so a deliberately
minimal capture is left to milestone 4 (model picker). Shape, from the live response and
`gateway/platforms/api_server.py:2434-2451`: `{providers: [...], model, provider}`, where the
top-level `model`/`provider` are the server default. Each provider has `slug`, `name`,
`is_current`, `is_user_defined`, `models` (list of model ids), `total_models`, `source`,
`authenticated`, `free_tier`, `pricing_pending`, `unavailable_models` and `capabilities`
(`{<model id>: {fast, reasoning, can_disable_reasoning}}`); some providers add `auth_type`,
`key_env` (an env var name), `warning`, `featured_models`, `pricing` and a `usage` object with
account usage windows (must never be stored or shown). Only providers with `authenticated: true`
are usable. Query parameters `refresh=true` and `include_unconfigured=false` exist.

### POST /api/sessions
Request `{"id": "...", "title": "..."}`. 201 `session_create.json`
(`{object: "hermes.session", session: {...}}`). Same id again: 409 `session_exists`
(`session_exists_409.json`). **An id that was deleted can never be reused:** 409
`session_deleted` (`session_recreate_deleted_409.json`). New ids must be unique forever, so
generate them from a UUID, not from a counter.

### GET /api/sessions/{id}
`session_get_archived.json`, `session_get_model_locked.json`. Works for archived sessions.

### PATCH /api/sessions/{id}
Accepts only `title`, `pinned`, `archived`, `hidden`, `unread`, `end_reason`; flags must be
JSON booleans; any other key is 400 `unsupported_session_field`
(`session_patch_unsupported_400.json`). Returns the full session like GET
(`session_patch.json` for title+pinned, `session_patch_archived.json`,
`session_patch_unarchived.json`). Pinning clears `hidden`
(`gateway/platforms/api_server.py:3147-3183`).

### GET /api/sessions?limit=&offset=
`{object: "list", data: [...], limit, offset, has_more}`. `sessions_list.json` is the raw body of
`GET /api/sessions?source=api_server&title=<unique probe title>&limit=50`; `title` is an exact
title match done on the server, so only the probe row came back.
`sessions_list_pinned_archived.json` is the same query for a pinned and archived probe row. Sorted by last activity. Rows add `last_active` and `preview` to the
session shape. `limit` default 50, max 200. Only `source`, `include_children`, `title` (+
`include_hidden` with a title) are honoured as filters.

**Archived rows are never listed**, except archived rows that are also pinned (the pin
back-fill returns them; see the second row of `sessions_list.json`). `archived=true`,
`include_archived=true`, `archived_only=true` and `archived=1` were all tried live (scoped to an archived probe row's title) and are
ignored: the handler never passes an archive flag (`gateway/platforms/api_server.py:3014-3061`).
Consequence for the app: see decision (a) below. Leftover-session checks must use direct GETs
for the same reason.

### POST /api/sessions/{id}/model
Request `{"model": "...", "provider": "..."}`. 200 `session_model_lock.json` (recorded with the
placeholder ids `example-model` / `example-provider`: the server accepts the lock without checking
that the model exists)
(`{object: "hermes.session.model_lock", session_id, runtime: {provider, model, route_source,
requested: {provider, model}, model_lock: "accepted"}}`). Afterwards the session shows
`model` set and `has_model_config: true`. In source the persisted lock is read only by
`/api/sessions/{id}/chat[/stream]` (`api_server.py:2068-2074`, used at `api_server.py:3327`).
For `/v1/runs`, send `model`, `provider` and `model_options` in each run body (decision (b)
below): the docs state the native `/v1/runs` endpoint always honours them (`website/docs/user-guide/features/api-server.md:478-516`).
`model_options` keys: `reasoning: {enabled, effort}` or `reasoning_effort`, `service_tier`
or `fast` (`gateway/platforms/api_server_request_options.py`).

### DELETE /api/sessions/{id}
200 `session_delete.json` (`{object: "hermes.session.deleted", id, deleted: true}`). 409
`session_active_turn` while a turn is running (`api_server.py:3207-3208`): stop first.

### GET /api/sessions/{id}/messages?order=oldest&include_compacted=true
`{object: "list", session_id, data: [...], pagination: {limit, offset, order, returned}}`.
Default and max page 500. Row keys: `id`, `session_id`, `role`, `content`, `tool_call_id`,
`tool_calls`, `tool_name`, `timestamp`, `token_count`, `finish_reason`, `reasoning`,
`reasoning_content`, `display_kind`. Fixtures: `messages_tool_run.json` (tool turn followed by
the queued second turn), `messages_steer.json`, `messages_after_stop.json`.

Observed turn layout (an example, not a grammar): `user` (`display_kind: "internal_notification"`), `assistant` with
`content: ""`, `finish_reason: "tool_calls"` and `tool_calls[]` (`id`, `call_id`,
`response_item_id`, `type`, `function: {name, arguments}`), `tool` row (`tool_call_id`,
`tool_name`, `content` is a JSON string `{"output", "exit_code", "error"}`), final `assistant`
(`finish_reason: "stop"`), and a `session_meta` row with `content: null`.

Transcript delimiting: **neither `session_meta` nor a newline is a reliable delimiter.** In
`messages_tool_run.json` the first turn is followed by `session_meta`, but the queued second turn
ends with its assistant row and no `session_meta` (the row may be written later or not at all).
A turn starts at a `user` row with `display_kind` null or `internal_notification`; a `user` row
with `display_kind: "steer"` belongs to the running turn. Group rows by these user rows and by
order, ignore `session_meta` for layout, and never split message content on newlines.

### POST /v1/runs
Request `{"session_id", "input"}` plus optional `model`, `provider`, `model_options`, header
`Idempotency-Key`. 202 `run_start.json` (`{run_id, status: "started", replayed: false}`).
Identical retry with the same key: 202 with the same `run_id`, `replayed: true`, header
`Idempotency-Replayed: true` (`run_start_replayed.json`; its `status` said `queued` although
the run was running, so poll `GET /v1/runs/{id}` instead of trusting it). Same key, different
body: 409 `idempotency_key_conflict` (`run_idempotency_conflict_409.json`).

### GET /v1/runs/{id}
`{object: "hermes.run", model, runtime?, created_at, pending_controls: [], run_id, status,
session_id, admission_id, execution_generation, output, usage}`. Fixtures:
`run_status_running.json`, `run_status_queued.json` (`execution_generation: null` while
queued), `run_status_completed.json` (adds `runtime`; `usage: {input_tokens, output_tokens,
total_tokens}`), `run_status_completed_queued.json` (the queued run after it ran,
generation 2), `run_status_cancelled.json`. Unknown run: 404 `run_not_found`
(`error_404_run.json`).

### GET /v1/runs/{id}/events (SSE)
Each attach starts with the comment `: open`, then frames `id: <seq>` + `data: <json>` + blank
line. Comments `: keepalive` every 10 s of silence. Run events are
`{event, run_id, timestamp, ...fields, seq}`. Exceptions a parser must handle
(`gateway/platforms/api_server_runs.py:1212-1302`):

- `replay.truncated {oldest_retained_seq, requested_seq?}` is sent as a frame **without `id:` and
  without `seq`** when the requested replay point fell out of the 1000-event buffer. Resume from
  `oldest_retained_seq` and re-sync the transcript from the messages endpoint.
- The stream normally ends with `: stream closed`, but it can also end at **plain EOF** with no
  closing comment: a slow subscriber is dropped on queue overflow (line 1288), a write that
  times out after 5 s closes the connection (lines 1241-1249), and network loss looks the same.
  Treat EOF without `: stream closed` as "reconnect with `Last-Event-ID`", not as run end; the
  run's end is `run.completed`/`run.failed`/`run.cancelled` or a terminal `GET /v1/runs/{id}`.

- Drop and resume (`events_tool_run.sse` = first connection then the resumed one, exactly as
  received; `events_tool_run_resumed.sse` = the resumed connection alone): the first
  connection was dropped after seq 0, the second sent `Last-Event-ID: 0` and received seq 1 on.
  The resumed connection begins with its own `: open`.
- Events seen: `tool.started {tool, preview, tool_call_id}`, `tool.completed {tool, duration,
  error, tool_call_id, preview}`, `message.delta {delta}`, `run.steered {accepted}`,
  `run.completed {output, usage, runtime, completed: true, partial: false, interrupted: false}`,
  `run.cancelled {completed: false, partial: false, interrupted: true,
  turn_exit_reason: "interrupted_by_user"}`.
- The first `message.delta` of the reply after a tool call starts with `"\n\n"`.

### POST /v1/runs/{id}/steer
Request `{"text": "..."}`. 200 `steer_response.json` (`{object: "hermes.run.steer", run_id,
accepted: true}`); one `run.steered` event per steer (`events_steer.sse`).

### POST /v1/runs/{id}/stop
200 `run_stop.json` (`{run_id, status: "stopping", admission_id}`), then the stream emits
`tool.completed` with `error: true` for the interrupted tool and `run.cancelled`
(`events_stop.sse`). Final status `cancelled`. For a queued run the response is the run
projection instead (`gateway/platforms/api_server_authority_runs.py:138-168`).

### POST /v1/runs/{id}/approval and /clarify
Source-derived (not provoked live). Body must be exactly `{request_id, execution_generation, choice}` or
`{request_id, execution_generation, answer}`; `request_id` is the prompt's `prompt_id`;
`choice` is one of the prompt's `choices`. Success is 200
`{"run_id": "run_...", "status": "resolved", "prompt_id": "..."}`, or
`"status": "already_resolved"` when the prompt was answered or expired in between
(`api_server_runs.py:1372-1379` wraps `session_pending_controls.py:66-101`). A `*.settled` event
follows on the stream.
Extra keys are 400 `invalid_params`; a wrong generation is 409 `stale_generation`; a prompt
already answered is 409 `approval_not_pending`
(`gateway/platforms/api_server_authority_runs.py:113-135`, status mapping at lines 18-35).

### POST /v1/runs/{id}/resolve-unknown
Source-derived (`api_server_runs.py:1487-1505`, `api_server_authority_runs.py:170-189`). Used when
`GET /v1/runs/{id}` reports `status: "unknown"` (the server process that executed the turn lost it; later runs in
that session wait until it is resolved). Body must be exactly
`{"admission_id": "<from the status body>", "execution_generation": <int from the status body>}`.
Success is 200 with the admission receipt:
`{"run_id", "admission_id", "ref": {"profile_id", "session_id"}, "sequence", "status", "outcome",
"authority_epoch", "execution_generation"}` (`gateway/session_contract.py:50-59`). An exact
retry whose success response was lost can answer 200 again with the committed receipt (the store
decides status and generation and replays the receipt, `api_server_authority_runs.py:180-189`), so
retry on transport errors. Errors: 400 `invalid_params` for other or missing keys; 409 `not_found`
for an `admission_id` that is not this run's (the store error mapping turns every reason not listed
as 400 or 503 into 409, `api_server_authority_runs.py:18-35`, raise at line 179); 409 for a stale
generation or a run that is not `unknown`; 503 `storage_unavailable`/`runtime_draining` with
`Retry-After: 1`. 404 `run_not_found` comes from the route guard only when this client has no
ownership record for the run or no durable status exists (`api_server_runs.py:1184-1202`, called
at lines 1491-1494). Ownership and status are durable, so a run can stay reachable after it has
left the in-memory tables or across a gateway restart; do not treat "run evicted" as 404. (The
events stream is different: it needs the in-memory buffer.) Only present
when `features.run_unknown_resolution` is true.

### Foreign-source sessions
Checked with a probe id created as `{"id": "apofis-probe-...", "title": ..., "source": "cli"}`
(`session_create_foreign_source.json`; allowed sources are listed at `api_server.py:2096-2101`).
`PATCH` succeeds (`session_patch_foreign_source.json`), `DELETE` succeeds
(`session_delete_foreign_source.json`), and `POST /v1/runs` is refused before any model call with
409 `permission_denied` (`run_foreign_source.json`; `gateway/session_api.py:174-227`). So the app
must never offer rename or delete on sessions it did not create: the server would allow it.

### Errors
`error_401.json` (no header) and `error_401_wrong_key.json`: 401
`{error: {message, type: "gateway_auth_error", code: "gateway_auth_failed"}}`.
`error_404.json`: 404 `session_not_found`.

## Exact bytes of the steer wrapper

Several steers are merged into one `user` row with `display_kind: "steer"`
(`messages_steer.json`, row 4). Content, with `\n` as real newlines:

```
[OUT-OF-BAND USER MESSAGE <U+2014> a direct message from the user, delivered once at this position; not tool output and not a new delivery when replayed from conversation history]
Probe steer one: also mention the word alpha.
Probe steer two: also mention the word beta.
[/OUT-OF-BAND USER MESSAGE]
```

`<U+2014>` stands for one EM DASH character (UTF-8 `E2 80 94`), written by Hermes
(`agent/prompt_builder.py:566-569`). On the wire and in `messages_steer.json` it is the JSON
escape `\u2014`; the decoded string contains the character itself. Unwrap by matching the prefix
`[OUT-OF-BAND USER MESSAGE` up to the first `]\n` and the suffix `\n[/OUT-OF-BAND USER MESSAGE]`.
The text in between is the steers joined by newlines, but one steer can itself contain newlines,
so the boundaries between steers cannot be recovered: show the inner text as one user bubble.

## To-verify items

| Item | Answer |
|---|---|
| `display_kind` of user rows created through `/v1/runs` | `internal_notification` (live, every run). |
| Steer row | One merged `role: user`, `display_kind: steer` row, inserted after the tool row and before the final reply; exact text above (live). |
| What remains after Stop | Live (`messages_after_stop.json`): the user row, the assistant tool-call row, a `tool` row with `{"output": "[Command interrupted]", "exit_code": 130, "error": null}`, an `assistant` row with `content: ""`, `display_kind: "hidden"`, then `session_meta`. The model's pre-tool sentence ("I<U+2019>m starting the probe.", with a typographic apostrophe as sent) was **not** streamed as `message.delta` (`events_stop.sse` has none) and is stored in `reasoning` and `reasoning_content` of the assistant tool-call row (row 16407), not in `content`. Hermes can store text the model sent outside the final answer under `reasoning` instead of `content`. No partial streamed reply was cut off, so that case is still unobserved; `run_status_cancelled.json` has `output: ""`. The app should render non-empty `reasoning` on an assistant row as the collapsible reasoning step. |
| Final run status after Stop | Stop answers `stopping`; status then becomes `cancelled`; event `run.cancelled` (live). |
| Archived sessions in `GET /api/sessions` | Not returned by default and no query parameter selects them; pinned+archived rows are returned (live, all four parameter guesses ignored; `api_server.py:3014-3061`). |
| PATCH accepts / returns | `title`, `pinned`, `archived`, `hidden`, `unread`, `end_reason`; returns the full session (live + `api_server.py:3147-3183`). |
| `GET /api/model/options` body | Shape described above from the live response; no fixture (privacy), deferred to milestone 4. |
| Model per run / per session | Per run: `model`, `provider`, `model_options` in the `/v1/runs` body (docs). Per session: `POST /api/sessions/{id}/model` accepted live (`session_model_lock.json`), but in source only session chat reads the lock; not verified for `/v1/runs`. Settled by decision (b): the app sends `model`/`provider` with every run. |
| `tool.completed.duration` | Always `0` in canonical mode: the completion callback passes no duration (`gateway/platforms/api_server_runs.py:1082-1085`, rounded at line 100). `preview` is always `""` there too (no `result` passed). Seen live for 20 s tools. Use `tool.started`/`tool.completed` timestamps instead. |
| `pending_controls` | Live: always `[]` in the fixtures. Shape when not empty: list of prompts from `gateway/session_pending_controls.py:27-49` (below); status turns `waiting_for_approval` when one has `kind: approval` (`api_server_authority_runs.py:90-94`). |
| `approval.request` | Could not provoke with the approval settings of the server used for recording (a command the default policy flags, `tools/approval_detection.py:287`, ran without a prompt; the script now uses the inert `echo 'DROP TABLE apofis_probe_noop'`, flagged by the "SQL DROP" pattern at `tools/approval_detection.py:296`, and no longer changes any file mode). Shape taken from Hermes source at `gateway/session_pending_controls.py:18-38`, forwarded to the run stream by `gateway/platforms/api_server_runs.py:1072-1076` (below). |
| Clarify request | Could not provoke: the `hermes-api-server` toolset excludes the clarify tool (`toolsets.py:196-199`); the model answered in text. Shape taken from Hermes source at `gateway/session_pending_controls.py:40-51` (below). |
| `reasoning.available` | Could not provoke: a run with `model_options.reasoning.effort: "high"` produced no such event and that run stored no `reasoning` (the Stop run did store commentary text in `reasoning`, see above). In canonical mode `/v1/runs` wires only text, tool start and tool complete callbacks (`gateway/platforms/api_server_runs.py:1078-1089`), so the event is not emitted. Non-canonical shape: `{event: "reasoning.available", run_id, timestamp, text}` (`api_server_runs.py:102`). |
| Compressed session | Not provoked (needs a long chat). Source: see "Compression" below. |
| PATCH/DELETE on foreign-source sessions | Live, on a probe id created with `source: "cli"`: PATCH 200, DELETE 200, `POST /v1/runs` 409 `permission_denied`. |
| Push plugin reading registration rows from a hook | Milestone 3: a shell hook, not a plugin. See "Push hook (milestone 3)" below and `server/apofis-push/README.md`. |
| Foreground service type on GrapheneOS | Deferred, and superseded by decision "No foreground service" (spec section 11); any device check happens in a later milestone. |

## Decisions (reviewer acting as decider, 2026-11-19)

- **(a) Archive.** Archive sets `archived: true` on the server and keeps the pin state. Because
  archived rows are not listed, the app keeps archived ids locally and refreshes each with
  `GET /api/sessions/{id}`. A fresh install cannot rediscover unpinned archived chats.
- **(b) Model per chat.** The per-chat model choice is stored locally and sent as `model` and
  `provider` with every `/v1/runs` request. The server-side session model lock is not relied on.

## Source-derived shapes (not observed live)

`approval.request` event on `/v1/runs/{id}/events` in canonical mode (envelope from
`_run_event`, payload from `PendingControls.register`):

```json
{"event": "approval.request", "run_id": "run_...", "timestamp": 0.0,
 "kind": "approval", "prompt_id": "...", "execution_generation": 1,
 "command": "<redacted command>", "description": "...",
 "choices": ["once", "deny", "session", "always"], "timeout_s": 300, "seq": 3}
```

`choices` is `["once", "deny"]` plus `session` and `always` unless disallowed or smart-denied;
an `edit` object may be present (then choices are `["once", "deny"]`); `timeout_s` only when
set. The same prompt object (without the envelope) appears in `pending_controls` on
`GET /v1/runs/{id}` while the run is `waiting_for_approval`.

Clarify request event:

```json
{"event": "clarify.request", "run_id": "run_...", "timestamp": 0.0,
 "kind": "clarify", "prompt_id": "...", "execution_generation": 1,
 "question": "...", "choices": ["red", "blue"], "multi_select": false, "timeout_s": 120, "seq": 3}
```

When a prompt is answered or expires the stream gets `approval.settled` or `clarify.settled`
`{prompt_id, execution_generation}` (`session_pending_controls.py:62-63`, forwarded by the set at
`gateway/session_api_turn.py:434`). The non-canonical path (not used on this server) emits
`approval.request` built by `_approval_request_event` (`api_server.py:114-127`) and
`approval.responded {choice, request_id?, resolved}` (`api_server_runs.py:1366`).

### Compression

- List: the row is shown under the newest segment's `id`, with that segment's `title`,
  `message_count`, `last_active`, `preview`, `ended_at`, `end_reason`, `model`; the root's
  `pinned`/`archived`/`hidden` stay (`hermes_state_sessions.py:1133-1157`). The database layer
  also adds `_lineage_ids` and `continuation_kind: "compression"`, but the API server's
  session projection only passes `_lineage_root_id` through (`api_server.py:2934-2942`).
  So on this API: a listed row whose `_lineage_root_id` differs from `id` is a compressed chat;
  `_lineage_root_id` is the id the app created.
- Messages: the endpoint resolves any id in the lineage to the live tip
  (`resolve_resume_session_id`, `hermes_state_messages.py:1426`) and returns rows of all
  segments oldest first (`include_ancestors=True`); compacted rows only with
  `include_compacted=true`. The response's `session_id` is the resolved tip, which can differ
  from the requested id (`api_server.py:3230-3256`).
- `/v1/runs` with an old segment id adopts the live tip (`api_server_runs.py:788-790`), and
  the run status's `session_id` reports the id actually used.

## Push hook (milestone 3)

Source-derived at `a8fed44bbe`, not observed live (the owner's handoff checks it). The script is
`server/apofis-push/apofis_push.py`.

- **Runner.** `hooks: <event>: [{command, timeout}]`. Hermes applies `os.path.expanduser` to the
  whole command string and splits it without a shell, so only a leading `~` expands; the hook runs
  in its own process group with stdin, stdout and stderr piped, and `communicate(timeout)` waits for
  EOF on both outputs, so a detached child must not inherit them (`agent/shell_hooks.py:479-531`).
  Timeouts and non-zero exits of observer hooks are only logged (`:554-610`). Consent is the exact
  `(event, command)` pair in `~/.hermes/shell-hooks-allowlist.json` (`:305-325`); the gateway
  re-reads configuration and allowlist about every 30 s (`sync_from_config`, `:180-234`).
  `hermes hooks test <event>` runs every hook configured for that event without checking consent,
  with sample or custom payloads (`hermes_cli/hooks.py:179-221`); `doctor` runs approved hooks only
  (`:309-325`). Samples: `platform: "cli"` for `on_session_end`, no `kind` for
  `on_human_input_request` (`:100-130`).
- **SQLite read-only opens create sidecars.** A `mode=ro` connection opens `-shm` with
  `O_RDWR|O_CREAT` and `-wal` with READWRITE|CREATE before falling back to read-only (sqlite3.c
  3.53: `unixOpenSharedMemory`, `sqlite3WalOpen`, `unixOpen`). On a WAL database whose last writer
  has closed (sidecars deleted), such a reader creates both files in a writable directory (checked on
  a private fixture). The hook accepts this as SQLite's normal reader behaviour; it never changes
  the database content.
- **Stdin.** `{hook_event_name, tool_name, tool_input, session_id, cwd, profile, extra}`;
  `session_id` is top level, every other keyword argument is under `extra` (`shell_hooks.py:49,87-102`).
- **`on_session_end`** (`agent/turn_finalizer.py:794-812`): `extra` has `task_id, turn_id, completed,
  failed, interrupted, turn_exit_reason, model, platform`; not fired when persistence is disabled.
  The script pushes `finished` only when `completed` or `failed` is `true`.
- **`on_human_input_request`** (`tools/human_input_hooks.py:38-72`): `extra` has `kind`
  (`approval`, `clarify`, `sudo`), `request_id`, `session_key`, `platform`, `prompt` (force-redacted,
  still not sent). A failed identity lookup empties both `session_id` and `platform`; otherwise
  `platform` falls back to `cli`.
- **Registration records** (the app writes them in Task 2): session id
  `apofis-push-<installId>-<seq>` (lowercase UUID, seq `[1-9][0-9]{0,5}`), `source = 'api_server'`,
  `hidden = 1`, prompt `{"v":1,"endpoint":"<url>"}` of at most 1024 characters, read as
  `COALESCE(system_prompts.prompt, sessions.system_prompt)` (`hermes_state_sessions.py:1579-1584`).
- **Lineage.** A row continues its parent when the parent's `end_reason = 'compression'`, the row's
  source is not `tool`, and none of `_branched_from`, `_delegate_from`, `_reset_from` in its
  `model_config` equals the parent id; an unparsable `model_config` counts as no marker
  (`hermes_state_messages.py:1878-1897`, `hermes_state_compression.py:906-925`). The push names the
  root reached this way, only through visible, non-reserved `api_server` rows.
