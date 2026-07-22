# Reference-only files (excluded from the build)

This folder is outside `src/`, so nothing here is compiled — Gradle's default
Android/Kotlin source sets only look under `terminalApp/src/main/...`. Files
are also renamed to `*.reference.kt.bak` so they can't be mistaken for live
source or accidentally re-included by a future source-set change.

## Why these files are archived, not deleted

`TerminalWorkflowModels.reference.kt.bak` / `TerminalWorkflowScreens.reference.kt.bak`
were an earlier, never-wired-up attempt at a supplier-workflow UI (standby,
login, key pickup with Layout/List toggle, return, management menu, etc.).
An audit against the Smart Key Cabinet User Manual V2.1 (see CLAUDE.md's
"Terminal App UX Baseline") found it deviates from the manual in several
concrete ways and must not be merged or adapted as-is:

- The return flow adds an extra "confirm key inserted" step/button; the
  manual's return flow ends at insert-and-done, no extra confirmation.
- Settings toggles surface recording state to the operator via notice
  banners during pickup/return; the manual requires recording to be
  silent and never a user-facing step.
- The "Key Return Certification" setting is labeled "Authentication for
  key returns" — a paraphrase, not the manual's exact term.
- The login screen adds a "Digital Key" method not in the manual's list,
  and doesn't distinguish personnel-card swipe from key-card swipe (the
  manual has key-card swipe return a key directly, bypassing login).
- `TerminalRoute.RETURN_SCAN` has no corresponding screen in
  `TerminalWorkflowScreens.reference.kt.bak` (see
  `FobEnrollmentScreen.reference.kt.bak` for `ReturnScanScreen`, which was
  actually built against the older `PublicCardReaderController` path).

`FobEnrollmentScreen.reference.kt.bak` is included here too, not because it
was named directly, but because it imports `WorkflowKey`/`WorkflowPage`/
`NoticeCard` from the two files above and would fail to compile once those
move out of `src/`. It has the same never-reached status (no caller in
`MainActivity`/`TerminalAdminApp.kt`) and is worth checking before reusing:
it has its own `ReturnScanScreen`, and uses `PublicCardReaderController`
(`terminalApp/src/main/java/com/ekms/terminal/hardware/PublicCardReaderController.kt`),
which is still live in the build (unused elsewhere, but functional) and is
the right hook point for real personnel/key-card swipe hardware later.

Consult these files for structure and prior art, but rebuild each screen
directly against the manual, one phase at a time, rather than adapting them.
