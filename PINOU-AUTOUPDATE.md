# PinouTube auto-update

PinouTube (this fork, branch `tabbed-grid-home`, package `home.pinou.smarttube`) never updates itself.
Every night `pinou-sync.ps1` merges the official SmartTube changes (`upstream/master`), builds the release
APK and installs it on the Shield. An agent only steps in when that fails.

## How it runs

- **Windows task "PinouTube auto-update"**: daily at 03:00, and as soon as possible after a missed start
  (PC off or asleep). It runs `pinou-sync-hidden.vbs`, which starts `pinou-sync.ps1` with no window.
  Recreate it with `.\pinou-sync.ps1 -Register`.
- **PiDex schedule, daily at 03:30**: messages the "PinouTube auto-update" conversation in the "Nas & PC"
  project. Its agent runs `.\pinou-sync.ps1 -Check`, which waits for a run in progress, runs the update
  itself if none happened since 03:00, prints the result and exits 1 only if the update failed.
  PiDex sends a missed message as soon as it is running again.
- **State** (not in git): `.pinou-sync\status.json` (state, step, summary, last built commit, last
  installed APK hash) and `.pinou-sync\last-run.log` (the last run only).

## What a run does

1. Stops without touching anything if the fork isn't on `tabbed-grid-home` or has uncommitted changes
   to tracked files.
2. `git fetch upstream` (3 tries). If `upstream/master` has new commits: `git merge`, then
   `git submodule update --init --recursive`. On a conflict it aborts the merge and fails.
3. Builds with `pinou-build.ps1 -NoDaemon` when something other than these scripts and docs changed
   since the last build. If the build fails after a merge, it puts the branch back where it was (the installed app stays as it is) and fails.
4. Pushes the branch to `origin` after a merge. A failed push is only a warning.
5. Installs `dist\PinouTube.apk` when the Shield's copy differs, only while PinouTube isn't the app on
   screen (or the Shield is asleep or dreaming). It checks every 5 minutes for up to 4 hours (not at all
   for `-Check` runs), then postpones to the next run. 3 postponed runs in a row count as a failure.

## When a run fails (updater agent)

1. Read `.pinou-sync\status.json` (step and summary) and `.pinou-sync\last-run.log`.
2. Fix the cause. For a merge conflict, keep both the official change and the fork's feature (list
   below). `git log --first-parent upstream/master..HEAD` shows the fork's own commits.
3. Apply the update by hand: `git merge upstream/master`, `git submodule update --init --recursive`,
   `.\pinou-build.ps1`, then install `dist\PinouTube.apk` with adb, only when `Get-ShieldState` in
   `pinou-sync.ps1` would say `free`. Check that app right before installing: installing closes it.
4. Future-proof: change `pinou-sync.ps1` (or the fork) so this same cause can't stop the next run on its
   own, for example by resolving that kind of conflict automatically, retrying, or moving a fork change
   out of an upstream file that changes often. Update this file if the process changes.
5. Commit and push, then run `.\pinou-sync.ps1 -Check`: it must exit 0.

Rules:
- Never discard or commit uncommitted work you didn't make: if a run failed because of someone's
  work in progress, say so and leave it.
- Never press keys, open apps or play anything on the Shield while someone may be using it.
- Keep one APK in `dist` (`pinou-build.ps1` does this). Leave no scratch files behind.
- Release builds are signed with the same key as the installed app (debug keystore), so an install
  is an in-place update that keeps the sign-ins. Never uninstall the app: that deletes the two accounts.

## Fork features to keep when resolving conflicts

- **`stpinou` flavor** (`smarttubetv/build.gradle`, `smarttubetv/src/stpinou/`): package
  `home.pinou.smarttube`, name PinouTube, PinouTube icons and banner (`branding/`), no update checks
  (it must never offer the official builds), no crash reports.
- **Home tabs** (`TabbedVideoGridFragment`, `BrowseSectionFragmentFactory`): a section's rows are tabs
  above an endless grid; the tab strip hides while scrolling down.
- **Playlist header** (`ChannelUploadsFragment`, `ChannelUploadsPresenter`, `PlaylistSorter`,
  `GridFragment` hooks): sort by, ascending/descending, remembered per playlist, "Scroll to end",
  earlier page loading.
- **"No reason"** in the Not interested dialog (`VideoMenuPresenter`).
- **Back buffer** (`BackBufferLoadControl`, `ExoPlayerInitializer`, `ExoPlayerImplInternal`): keeps up
  to 2 minutes behind the playhead for instant seeking back.
- **Subtitles after seeking** (`SubtitleManager`): the repeated-line filter resets on every seek.
- **Thumbnail cache** (`GlideCachingModule`, `ExpiringDiskCacheFactory`, `ViewUtil.glideCachedOptions`,
  card presenters): 200 MB on disk, entries expire after two weeks, plus the memory cache.
- **Build scripts**: `pinou-build.ps1` (release build, one APK in `dist`), `pinou-sync.ps1`,
  `pinou-sync-hidden.vbs`, this file.
