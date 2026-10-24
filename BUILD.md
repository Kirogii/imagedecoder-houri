# imagedecoder-houri — Bundled ARM64 decoder and native source builds

## Windows consumer default

When included by Questium, Windows builds package the durable consumer input
`native/decoder/arm64-v8a/libimagedecoder2.so`. This path is resolved relative to the
consumer repository, two directories above this included build. No WSL probe or CMake
configuration runs in this mode. The decoder module filters its ABI to `arm64-v8a`;
the consumer must also restrict APK outputs to ARM64. Explicit non-ARM64 requests through
`android.injected.build.abi` or `questium.abis` fail with an actionable error.

The library is checked for ELF64, little-endian encoding, AArch64 machine type and SHA-256:

```text
ff5eb9760fbd9c349875768ff7c610fd691d1d55f63f005b8b5e9b6f212db632
```

This is an imported legacy binary recovered from the existing working decoder. Its hash
identifies the exact imported bytes; it does not attest the source commit, compiler,
dependency versions or build flags that originally produced them. Consumer metadata lives
in `native/decoder/manifest.json`; the consumer must preserve the binary's dependency
notices and licenses with that bundle. The source recipes below describe rebuilding from
the checked-out sources, not verified provenance for the imported binary.

`-Pimagedecoder.prebuiltArm64=<path>` remains supported on every host. The path must end in
`arm64-v8a/libimagedecoder2.so` and match the same checksum and ELF checks. Its parent JNI
directory is packaged directly; no copies or generated build-cache outputs are required.
Legacy `src/main/jniLibs` files are ignored unless explicitly selected by that property.

Use `-Pquestium.decoder.fromSource=true` to opt into source builds on Windows. This cannot
be combined with `imagedecoder.prebuiltArm64`. Linux defaults to the source build, and can
also explicitly select the verified prebuilt. Source builds support the other Android ABIs
and retain the WSL requirements below on Windows.

## Native source build (WSL / Debian)

This repo's `library/src/main/cpp/CMakeLists.txt` is **designed for Debian WSL** on Windows. There is **no MSYS2 / Git Bash fallback** — on `WIN32` the build `FATAL_ERROR`s if `wsl.exe` is missing. All `ExternalProject` steps (meson, autotools, cmake) run via `wsl`'s Linux tools to avoid `C:/` vs `/c/` vs `C:\` path bugs (notably Meson 1.12.0 `gio/meson.build:925` `is_parent_path` assert, and `CC=C:/...` tokenised as variable `C`).

Native Linux (CI / bare Debian) builds run directly without WSL.

## 1. Requirements

### 1.1 Windows host (WSL path)

- **Windows 10/11 + WSL2** with a **Debian** distro (tested on `Debian GNU/Linux 13 trixie`, kernel `microsoft`/`WSL`). Ubuntu works but this README documents Debian.
- **Android SDK** with:
  - `cmake` **3.22.1** (`$ANDROID_HOME/cmake/3.22.1/bin/cmake.exe` / `ninja.exe`)
  - **NDK `28.2.13676358`** — must have *both* prebuilts:
    - `toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe` (Windows host)
    - `toolchains/llvm/prebuilt/linux-x86_64/bin/clang` (WSL Linux, native ELF — the build prefers this to avoid `Exec format error` and `C:/` vs `/mnt/c/` handling)
- **JDK 17+**, Gradle wrapper (`gradlew.bat`), Android Gradle Plugin as in `settings.gradle.kts`.
- `wsl.exe` discoverable at `C:/Windows/System32/wsl.exe` (and `Sysnative` for 32-bit Gradle daemons). The CMake probes `C:/Windows/System32`, `C:/Windows/Sysnative`, `$SystemRoot/System32`, and `PATH`, plus `wsl --status`. No other shell is used.

### 1.2 Debian WSL dependencies (what the CMake probes for)

Install inside Debian WSL:

```bash
sudo apt update
sudo apt install -y \
  build-essential autoconf automake libtool pkg-config \
  meson ninja-build cmake python3 nasm \
  make bash file sed grep gawk mawk \
  perl git curl unzip

# verify (versions from this repo's working WSL):
meson --version   # 1.7.0
ninja --version   # 1.12.1
cmake --version   # 3.31.6 on WSL (outer Windows cmake is 3.22.1 — both work)
pkg-config --version  # 1.8.1
nasm --version    # 2.16.01 (dav1d AArch64/asm, libavif, highway)
gcc --version     # 14.2.0 (only for host checks; cross uses NDK clang)
autoconf --version # 2.72
automake --version # 1.17
libtoolize --version # libtool 2.5.4
```

What each is for:

| Dep | Probed in CMake | Consumer |
|-----|-----------------|----------|
| `make` (`wsl make`) | `BASH_VIA_WSL` + `Make_EXECUTABLE` | `libffi`, `lcms2`, `libiconv` autotools (`BUILD_COMMAND`) |
| `bash` (`wsl /usr/bin/bash`) | `BASH_EXECUTABLE` | `autotools_helper.sh`, `patch_iconv.sh` |
| `meson` (`wsl /usr/bin/meson`) | `Meson_EXECUTABLE` | `dav1d`, `libde265`, `glib`, `libvips`, etc. |
| `pkg-config` | `Wsl pkg-config probe` | `glib` / `libvips` `declare_dependency` |
| `autoreconf`/`autoconf`/`automake`/`libtoolize` | `autoreconf` probe | `lcms2`/`libffi` `autogen.sh` |
| `ninja` | `Ninja_EXECUTABLE` | `ExternalProject` + `meson -backend ninja` |
| `cmake` (WSL `/usr/bin/cmake`) | `WSL_CMAKE_PATH` | `libwebp`, `zlib`, `libpng`, etc. (toolchain still via NDK) |
| `python3` | via `meson` | `meson` itself |
| `nasm` | `nasm --version` (dav1d probe via meson) | `dav1d` AArch64 asm, `highway`, `libavif` |
| `wslpath`/`cygpath` | helpers (`meson_helper.sh`, `autotools_helper.sh`) | `C:/` → `/mnt/c/` conversion |

> **NDK Linux prebuilt is not an apt package.** The CMake at `library/src/main/cpp/CMakeLists.txt:591` hard-codes `/usr/lib/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang` and verifies `file` shows `ARM aarch64` / `x86_64 ELF` vs `x86_64 COFF` for the Windows `.exe`. If you installed the NDK via `sdkmanager` elsewhere, symlink or edit that path.

### 1.3 Native Linux (no WSL)

Same apt set as above; no `wsl.exe` needed. Outer `cmake` can be distro `3.31+` or SDK `3.22.1`. Set `ANDROID_HOME` / `ANDROID_NDK_HOME` to the NDK above. The CMake falls through the `IS_HOST_WINDOWS=FALSE` path (`to_msys_path` becomes a no-op).

## 2. Architecture

```
Windows gradlew.bat
  → C:/.../cmake/3.22.1/bin/cmake.exe (outer, WIN32)
    → checks wsl.exe, sets USE_WSL_TOOLS=TRUE
    → generates $build/wrappers/{clang,ar,ld} -> /init + wslpath
    → EP_BASE=.../ep  EP_PREFIX=.../fk  (short paths avoid MAX_PATH)
    → android-cross-file.txt (c = /usr/lib/.../linux-x86_64/bin/clang --target=aarch64-linux-android24)
    → meson_helper.sh, autotools_helper.sh, patch_iconv.sh (CRLF stripped)
    → for each ExternalProject:
        CONFIGURE:  cmake -E env WSLENV=CC:CCAS:... CC=/usr/.../clang ... wsl bash helper SOURCE ./configure --host=aarch64-linux-android ...
        BUILD:      cmake -E env WSLENV=... CC=... wsl bash helper SOURCE make -j$(nproc) all
```

* `to_msys_path()` converts `C:/...` → `/mnt/c/...` on Windows, leaves `/usr/...` unchanged.
* `WSLENV` forwarding is required — `cmake -E env` before `wsl.exe` does **not** reach Linux otherwise. `config.log` symptom was `CC='gcc'` (host) instead of `CC='/usr/lib/.../clang'`. Fixed via `WSL_ENV_FORWARD="CC:CCAS:CFLAGS:CCASFLAGS:CXX:CXXFLAGS:AR:LD:RANLIB:STRIP:..."`.
* Meson cross file uses Windows `C:/` `c_args` when falling back to `windows-x86_64`, but Linux `WSL` `/mnt/c/` `c_args` when `_use_linux_ndk` (so Linux clang understands the path).
* Fakeroot `fk/` is `/mnt/c/.../fk` on WSL, `C:/.../fk` on outer.

## 3. Build instructions

### 3.1 Windows (Debian WSL — the supported path)

```powershell
# 1. Ensure WSL works (elevated PowerShell once):
wsl --install   # then reboot, then `wsl --set-default Debian`

# 2. Inside Debian WSL — install deps (see §1.2):
wsl bash -c "sudo apt update && sudo apt install -y build-essential autoconf automake libtool pkg-config meson ninja-build cmake python3"

# 3. Verify WSL + tools from Windows side (what CMake does):
wsl --status
wsl bash -c "make --version; meson --version; pkg-config --version; autoreconf --version"
wsl bash -c "ls -l /usr/lib/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"

# 4. Build from repo root (Windows):
.\gradlew.bat :library:assembleDebug --rerun-tasks
# or for the host app that embeds this as a submodule:
.\gradlew.bat :external:imagedecoder-houri:library:assembleDebug --rerun-tasks

# 5. If you hit "WSL is required" FATAL:
#    - check wsl.exe at C:/Windows/System32/wsl.exe and C:/Windows/Sysnative/wsl.exe
#    - run `wsl --status` and `wsl bash -c "command -v make && command -v meson"`
#    - kill any 32-bit Gradle daemon that cached a stale WSL probe: ./gradlew --stop

# 6. Stale cross-file / autotools cache (common after switching NDK or editing CMakeLists.txt):
Remove-Item -Recurse -Force external\imagedecoder-houri\library\.cxx -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force .gradle\configuration-cache -ErrorAction SilentlyContinue
.\gradlew.bat --stop
.\gradlew.bat :library:assembleDebug --no-configuration-cache --rerun-tasks
```

**Troubleshooting Windows:**

| Symptom | Cause | Fix |
|---------|-------|-----|
| `FATAL_ERROR: WSL is required ... wsl was not found` | 32-bit Gradle daemon can't see `System32\wsl.exe` | `gradlew --stop`, ensure `C:/Windows/Sysnative/wsl.exe` exists, retry |
| `ERROR: Cannot find specified cross file:  (empty)` | stale `build.ninja` with `EP_MESON_ARGS="--cross-file="` before `MESON_CROSS_FILE_PATH_WSL` was set | `rm -rf library/.cxx && gradlew --rerun-tasks` (fixed since `EP_MESON_ARGS` re-set after cross-file generation) |
| `Exec format error: /mnt/c/.../clang.exe` | WSL `binfmt` for `.exe` missing | Build now prefers Linux NDK `/usr/lib/.../linux-x86_64/bin/clang`; if you see this, run `wsl bash -c "/init /mnt/c/.../clang.exe --version"` or reinstall NDK via `sdkmanager --install "ndk;28.2.13676358"` inside WSL |
| `config.log: CC='gcc' / CCAS='gcc'` then `sysv.S:729 invalid operands for '|'` | `WSLENV` not forwarding `CC`/`CCAS` (host `gcc` builds `aarch64` asm) | Ensure `CMakeLists.txt` has `WSL_ENV_FORWARD` and `libffi.cmake` uses `WSLENV=${WSL_ENV_FORWARD}` (fixed); verify `fk/src/ep_libffi/config.log: CC='/usr/lib/.../clang'` |
| `/bin/bash: line 1: all-recursive: command not found` | `MAKE=""` forwarded via `WSLENV` shadowed `make` | `MAKE` removed from `WSL_ENV_FORWARD` (fixed) |
| `fatal error: sys/memfd.h: No such file or directory` during libffi `configure` | harmless probe (`result: no`), `HAVE_MEMFD_CREATE` still `yes` via linker test | ignore |

### 3.2 Native Linux (Debian trixie, CI)

```bash
export ANDROID_HOME=$HOME/Android/Sdk   # or /opt/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
# ensure NDK + cmake 3.22.1 are installed:
sdkmanager --install "cmake;3.22.1" "ndk;28.2.13676358"

sudo apt update && sudo apt install -y build-essential autoconf automake libtool pkg-config meson ninja-build cmake python3

./gradlew :library:assembleDebug --rerun-tasks
# submodule embedded:
./gradlew :external:imagedecoder-houri:library:assembleDebug --rerun-tasks

# stale cache:
rm -rf library/.cxx .gradle/configuration-cache
./gradlew --stop
./gradlew :library:assembleDebug --no-configuration-cache --rerun-tasks
```

No `wsl` involved — `CMAKE_HOST_WIN32` is false, `to_msys_path` is a no-op, cross file `c` is the NDK `clang` from `CMAKE_C_COMPILER` (which AGP sets to the NDK toolchain).

## 4. Reproducing the exact versions from this repo

From inside WSL (the probe used in CI):

```
Debian GNU/Linux 13 (trixie)
meson 1.7.0, ninja 1.12.1, cmake 3.31.6, pkg-config 1.8.1
gcc 14.2.0, autoconf 2.72, automake 1.17, libtool 2.5.4
clang 19.0.1 (NDK r28b, linux-x86_64 prebuilt, Target: x86_64-unknown-linux-gnu, cross --target=aarch64-linux-android24 → ARM aarch64 ELF)
```

Outer Windows cmake: `3.22.1` at `C:/Users/<you>/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe`.

## 5. Helpers

- `library/src/main/cpp/CMakeLists.txt` — WSL detection, `to_msys_path`, `WSL_ENV_FORWARD`, cross-file, wrappers (`$build/wrappers/{clang,ar,ld}` → `/init` + `wslpath -w`).
- `library/src/main/cpp/cmake/{libffi,lcms2,libiconv}.cmake` — `BUILD_IN_SOURCE true`, `WSLENV=${WSL_ENV_FORWARD}` + `CC/CCAS/CFLAGS/CCASFLAGS` → `autotools_helper.sh`.
- `library/src/main/cpp/cmake/{dav1d,glib,libvips,...}.cmake` — `meson_helper.sh` (`_wslpath_or_manual` + empty `--cross-file=` guard).
- `tests/wsl/` — `run_all.sh` / `run.ps1` unit tests for `to_msys_path`, `meson_helper.sh`, cross-file ordering, WSL detection.

## 6. Not supported

- **MSYS2 / Git Bash / MSYS make/meson** — intentionally removed. If you have `C:/msys64` on `PATH`, it is ignored on `WIN32`; the build will still use `wsl`.
- **macOS** — not documented here (no WSL); use native Linux path.

## License

Same as parent repo (see `LICENSE`).
