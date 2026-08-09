# Changelog

## Unreleased · 2026-08-09

- [`d4603a9`](https://github.com/MatDayProjects/material-android/commit/d4603a9e14ca766141d826a41b7fe60879051282) creates the validated runtime output parent before collection, while keeping the collector's refusal to replace an existing output leaf. The package build had finished; the missing parent was the only thing still waiting outside the door. / 建立驗證過嘅 runtime output parent，但保留 collector 拒絕覆蓋現有 output leaf 嘅規矩；package build 明明已經完成，得返個未出世嘅 parent 喺門口等緊。
- [`4a46d84`](https://github.com/MatDayProjects/material-android/commit/4a46d84a3ccb90de7f54a7e95fdcbed0c5ad7f90) fixes the native builder invocation by passing Termux's package directory instead of its `build.sh` file; the file was innocent, but the directory check was not accepting walk-in paperwork. / 修正 native builder 呼叫，傳入 Termux package directory 而唔係 `build.sh` 檔案；個檔案冇做錯事，係 directory check 唔收「檔案入場券」。
- [`0e4a4c4`](https://github.com/MatDayProjects/material-android/commit/0e4a4c4ddc13cc7f2f5c866b601ed4281e5fcb45) fixes the hosted patch-tree comparison so the native build checks the actual files in the pinned Termux revision. The first run caught this honest pre-build failure before Docker did any heavy lifting. / 修正 hosted patch-tree 對比，確保 native build 係睇 pinned Termux revision 入面真實檔案；第一輪已經喺 Docker 開工前捉到呢個好老實嘅失敗。
- [`67993c3`](https://github.com/MatDayProjects/material-android/commit/67993c3e7558938ac68c6f00dcf43b30c8adf2b0) adds the pinned, open-source Android QEMU build lane, native-library packaging for both host ABIs, immutable CI action/container inputs, complete ELF and APK/AAB checks, and a required-runtime emulator controller smoke test. The hosted native build and Android guest boot remain unverified. / 用鎖定版本嘅開源 QEMU 配方砌好兩個 host ABI、包入 nativeLibraryDir，再用 emulator 睇實 production controller；hosted native build 同 Android guest boot 仲未驗證。
- [`af43751`](https://github.com/MatDayProjects/material-android/commit/af43751fb7b1ed162242e739a131c36f6b4706d9) adds the version-1 guest-image manifest and boot contract, strict UTF-8/depth limits, image/kernel/initrd size and SHA-256 binding, Activity-safe QEMU ownership, bounded VNC touch/key input, SAF display names, backup exclusions, and API 37 editor evidence. Native QEMU packaging and verified Android guest boot remain unverified.
- [`8a7a1ba`](https://github.com/MatDayProjects/material-android/commit/8a7a1ba9172b8427c4ee40d3e36874f568729c6e) adds the private UNIX-socket VNC/RFB framebuffer boundary, a real running-profile display surface, collision-resistant asset paths, atomic replacement, bounded output, forced-stop verification, and cancellation before QEMU launch; guest boot and input remain unverified.
- [`08eeecc`](https://github.com/MatDayProjects/material-android/commit/08eeecc0e8162e399fa28df8ab806d2830496e85) adds the process-backed QEMU runtime boundary, private bounded asset materialization, lifecycle/output tracking, and API 37 emulator coverage; native guest boot and display transport remain explicitly unimplemented.
- [`1c2a439`](https://github.com/MatDayProjects/material-android/commit/1c2a43979bc8324529e71d28ec8ec44d3f85f6cb) wires the protected GitHub Actions keystore into the Gradle release APK/AAB variants and verifies the uploaded certificate and checksums.

## 0.1.0 · 2026-08-08

- Created the OpenVM Android project from an empty repository.
- Added local VM profile management, image URI import, JSON import/export, and local history.
- Added backend readiness reporting for AVF and QEMU boundaries.
- Added settings for language mode, funny levels, emoji decoration, display name, and theme.
- Added a bounded regex builder and keyboard command palette.
- Added unit/instrumentation test scaffolding and a GitHub Actions signing workflow.
