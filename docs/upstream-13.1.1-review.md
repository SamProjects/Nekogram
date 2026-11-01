# Upstream 13.1.1 integration review

Upstream: Nekogram/Nekogram `199abde0f100d92cee8d925337561633e6e11186`.
Previous upstream revision: `10a8a639a37c262b8a03e504706002f153d76995`.

## Findings and changes

- The new `AnalyticsHelper.captureException` path checked analytics consent but did not check the existing `sendBugReport` preference. Added that check, with all eight combinations of consent, analytics and null-exception state covered by a production-method regression test.
- Upstream now applies its download byte budget to video streams. Exempt the single urgent playback read from that bulk budget so the existing priority connection cannot be blocked by a full bulk allocation. Normal downloads retain the upstream policy; regression tests cover urgent offset zero, tail reads, normal budgets and force flags.
- Kept `com.haiwaidao.nekogram`, the removal of Firebase Analytics, custom forwarding/media functionality, cached-message jumps, startup improvements, explicit download cancellation and message-details lifecycle protection.
- Kept artifact-free APK publication directly from the build job to Releases. The reusable tag workflow no longer transfers APKs through Actions artifacts. Removed the temporary, pinned upstream-sync workflow now that local integration is available.

## Static review performed

Reviewed changed application network/request entry points, login/QR handling, recording lifecycle, wallet transaction entry points, build/dependency changes and submodule revisions. No new manifest permissions, packaged executable blobs, unexpected destination domains, dynamic code-loading entry points or shell execution paths were found in the application delta. Login QR parsing still accepts the existing passkey/session formats and uses the existing decryption/import flow. Wallet signing still uses the existing secret-phrase path. New recording functionality remains behind the existing experimental setting and explicit recording lifecycle.

SQLite `sqlite3.c` and `sqlite3.h` both match the official `https://www.sqlite.org/2026/sqlite-amalgamation-3540100.zip` byte for byte (3.54.1, source ID `555e07ef33f69a0957d24d8e555b702464a131d6f1607f4c3c1e4551e8e6ca76`).

The media submodule was compared by its two exact trees, rather than a merge-base comparison (the revisions diverged). Only seven files actually differ; changes cover FFmpeg JNI/build registration, an experimental annotation, formatting and HLS metadata construction. The jlatexmath fork change disables custom macro/environment definition. The Telegram-maintained tgcalls changes include negotiation and MTProto transport refactoring; its alternate transport uses the existing shared-key encrypted connection rather than unauthenticated plaintext.

## Validation

- Message details lifecycle and synchronous/asynchronous detector callbacks.
- 22 message-jump cache cases.
- 56 source-forwarding assertions and resource validation.
- 1,046 video startup/cancellation assertions.
- Playback connection/byte-budget and exception-report consent tests.
- All global-media cross-flow, paging, performance, reentry, scheduling and SQL scripts.
- Workflow YAML parsing and actionlint 1.7.12.

## Limits

This is a static change review and targeted regression validation, not proof that all dependencies are free of vulnerabilities. It does not replace device tests, an independent cryptographic audit of the call transport, or a complete third-party dependency audit. The Android release build and its published assets must also succeed before considering the release complete.
