# History persistence memory fix

## Cause and changes

The previous store built the complete JSON tree, then a complete JSON string and UTF-8 byte array on every save. The live 40,257,902-byte history stopped updating while the app repeatedly failed 72/144 MiB allocations under its 256 MiB heap limit. Save errors were discarded by `runCatching`.

- `ConversationJson` streams the existing version-2 format using Gson JsonReader/JsonWriter, including individual string fields. No whole-file string/byte array or whole-file JSON tree is created.
- Keeps message metadata, tool traces, agent tool-call history, reasoning, usage and attachments. No destructive migration is required.
- AtomicFile still commits only after serialization and flush; errors (including allocation errors) roll back the partial write.
- Reads use AtomicFile.openRead, retaining legacy `.bak` recovery.
- A failed/incomplete load blocks writes and attachment pruning, rather than overwriting the original history with an empty cache. Structurally incomplete conversation objects (missing/null messages or missing id) are rejected too; explicit empty message arrays remain compatible.
- Failed saves are logged and shown in a persistent global error banner. A later actual successful save clears it; superseded save jobs do not clear it.
- Existing limits remain 50 conversations and 500 UI messages per conversation, but conversations are now selected by newest updatedAt rather than insertion order, preventing newly created sessions being excluded once the cache reaches 50.

## Validation

```sh
CONVERSATION_HISTORY_FIXTURE_PATH=/private/path/to/history-backup.json \
  bash scripts/build-termux.sh :app:testDebugUnitTest \
  --tests dev.pocketagent.data.ConversationJsonTest \
  --tests dev.pocketagent.ui.SessionStateRouterTest --offline
bash scripts/build-termux.sh :app:assembleRelease --offline
bash scripts/test-history-store.sh /private/path/to/history-backup.json
```

The optional fixture stays private; tests print only sizes/counts, not conversation content. The device smoke test runs a separate app_process against private temporary data, never against the live history directory.

Verified a private real-history backup containing 11 conversations (~40 MB): decode / encode / decode equality. Synthetic >40 MB history, complete-field round trip, optional/unknown legacy fields, malformed file rejection, writer failure propagation, latest-session retention and cross-session global error state are covered.

On-device smoke tests use Android's real AtomicFile: no-history case, load-before-save guard, save/reopen, injected Error rollback, corrupt-file write protection, `.bak` recovery, and real-history save/reopen under a 268,435,456-byte heap.

Full suite before the final retention assertion update: 204 tests, 203 passed, 1 unrelated existing failure: AgentEngineEfficiencyPolicyTest.promptKeepsUnlimitedIterationAndOptimizesRepeatedWork expects obsolete independent-shell wording, while AgentEngine uses the shared toolchain policy. That policy/test is deliberately not changed by this fix.

## Deployment safety

Release APK: `app/build/outputs/apk/release/app-release.apk`. Certificate was verified to match the installed app. Do not uninstall/clear data. Updating the running Agent kills its process, so installation is intentionally deferred. Existing on-disk history was backed up privately before edits and was not rewritten by tests. Content already lost from memory cannot be reconstructed by this patch.
