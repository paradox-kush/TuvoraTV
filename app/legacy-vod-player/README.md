# Legacy fork VOD player (not compiled, not invoked)

This directory holds the fork's pinned TV VOD player that was replaced by upstream's
player (NuvioMedia `99fbd65d8`) on 2026-11-04. See
`research/tv-player-convergence-audit-2026-11-04.md` in the workspace root.

- The last commit where this code was live is tagged **`tv-fork-vod-player-final`**
  (`26260482b`). Use the tag for history, blame and bisecting.
- `main/` and `test/` mirror `app/src/main/java/...` and `app/src/test/java/...`.
  - `*.kt` files are fork-only player files, moved here with `git mv`.
  - `*.kt.txt` files are the fork's version of a file that upstream replaced under the
    same name (the upstream version now lives at the original path).
- Nothing here is in a Gradle source set (`app/build.gradle.kts` adds only
  `src/main/jniLibs`), so Gradle, Hilt, lint and Konsist (`ArchitectureTest` scans
  `app/src/main` only) never see it.
- Fork fixes still needed on the upstream player are re-carried as small adapters (audit
  §2 buckets B and C); look for `TODO(vod-converge Bx/Cx)` at the seams. Read the code
  here for reference, do not move it back wholesale.

**Delete this directory after the upstream VOD path is proven on device** (audit §7d).
