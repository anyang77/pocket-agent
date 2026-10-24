# Architecture

## Layers

```text
Compose UI
  |-- Agent chat / approvals / file browser / CodeMirror editor / terminal
  |-- AppViewModel
Agent core
  |-- model client
  |-- tool loop
  |-- command policy
  |-- workspace path and deletion policy
Runtime layer
  |-- RuntimeCapabilities and root probe
  |-- CommandRunner
  |-- embedded Termux bionic userland
  |-- future PTY
```

## Runtime capability model

Root availability is a runtime capability, not an app variant:

- no root: embedded Termux bionic userland and Android all-files access
- root granted: optional manual root terminal and future root chroot runtime
- root denied or unavailable: automatic fallback

The app probes `su` non-interactively during initialization and automatically
uses Root when the installed root manager grants it.

The app bundles the Termux bootstrap as an asset. On first launch it extracts
and relocates it into `files/termux/usr`, patches text paths and symlinks, and
installs the development toolchains there. No external Termux installation or
Ubuntu rootfs is used.

The Android target is API 30 so that `MANAGE_EXTERNAL_STORAGE` can be granted
through the system all-files access page. Because Android 10+ blocks direct
execution from writable app data for this target, the runtime launches bash
through `/system/bin/linker64` and loads the bundled `termux-exec` interceptor
to route child process execution through the same read-only linker.

The file editor is a WebView-hosted CodeMirror instance. It owns text layout,
scrolling, highlighting and completion; Compose only supplies the toolbar and
file/save lifecycle.

## Security boundary

The agent receives structured file and command tools. Workspace paths are
canonicalized and enforced before access. Deletion requires an explicit user
confirmation and is permanent; there is no hidden recycle-bin copy.

The embedded runtime is not a security sandbox: it shares the Android app UID.
Root chroot is a separate high-risk feature and must run with explicit user
consent.
