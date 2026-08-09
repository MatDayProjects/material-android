#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="$ROOT_DIR/native/qemu/qemu-build.json"

usage() {
  cat >&2 <<'USAGE'
Usage: build-android.sh --host-abi <arm64-v8a|x86_64> --output <directory>
       build-android.sh --verify-only

The build uses the exact QEMU source digest and the exact Termux package-tree
revision in native/qemu/qemu-build.json. It never stores the source, toolchain,
container, or generated runtime in the repository.
USAGE
  exit 2
}

host_abi=""
output=""
verify_only=false
while (($#)); do
  case "$1" in
    --host-abi) [[ $# -ge 2 ]] || usage; host_abi="$2"; shift 2 ;;
    --output) [[ $# -ge 2 ]] || usage; output="$2"; shift 2 ;;
    --verify-only) verify_only=true; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v realpath >/dev/null || { echo "realpath is required" >&2; exit 1; }

[[ -f "$MANIFEST" ]] || { echo "Native QEMU manifest is missing: $MANIFEST" >&2; exit 1; }
schema="$(jq -r '.schemaVersion' "$MANIFEST")"
[[ "$schema" == 1 ]] || { echo "Unsupported native QEMU manifest schema: $schema" >&2; exit 1; }
qemu_version="$(jq -r '.qemu.version' "$MANIFEST")"
source_url="$(jq -r '.qemu.sourceUrl' "$MANIFEST")"
source_sha256="$(jq -r '.qemu.sha256' "$MANIFEST")"
termux_repo="$(jq -r '.termuxPackages.repository' "$MANIFEST")"
termux_revision="$(jq -r '.termuxPackages.revision' "$MANIFEST")"
termux_package="$(jq -r '.termuxPackages.package' "$MANIFEST")"
termux_recipe="$(jq -r '.termuxPackages.recipe' "$MANIFEST")"
builder_image="$(jq -r '.termuxPackages.builderImage' "$MANIFEST")"
minimum_native_api="$(jq -r '.android.minimumNativeApi' "$MANIFEST")"
required_guest_page_size="$(jq -r '.android.requiredGuestPageSizeBytes' "$MANIFEST")"
packaging_executable_directory="$(jq -r '.android.executableDirectory' "$MANIFEST")"
packaging_library_search_path="$(jq -r '.android.librarySearchPath' "$MANIFEST")"

[[ "$qemu_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Invalid QEMU version pin" >&2; exit 1; }
[[ "$source_sha256" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid QEMU SHA-256 pin" >&2; exit 1; }
[[ "$termux_revision" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid Termux revision pin" >&2; exit 1; }
[[ "$termux_repo" == https://github.com/termux/termux-packages.git ]] || { echo "Unexpected Termux repository" >&2; exit 1; }
[[ "$termux_package" == qemu-system-x86-64-headless ]] || { echo "Unexpected Termux QEMU package" >&2; exit 1; }
[[ "$termux_recipe" == packages/qemu-system-x86-64-headless/build.sh ]] || { echo "Unexpected Termux QEMU recipe" >&2; exit 1; }
[[ "$builder_image" =~ ^ghcr\.io/termux/package-builder@sha256:[0-9a-f]{64}$ ]] || { echo "The Termux builder image must be an immutable digest pin" >&2; exit 1; }
[[ "$minimum_native_api" == 29 ]] || { echo "The native QEMU lane requires Android native API 29" >&2; exit 1; }
[[ "$required_guest_page_size" == 16384 ]] || { echo "The native QEMU lane requires a 16 KiB page-size contract" >&2; exit 1; }
[[ "$packaging_executable_directory" == nativeLibraryDir ]] || { echo "Native QEMU executables must be packaged in nativeLibraryDir" >&2; exit 1; }
[[ "$packaging_library_search_path" == nativeLibraryDir ]] || { echo "Native QEMU libraries must resolve from nativeLibraryDir" >&2; exit 1; }
patch_count="$(jq '.termuxPackages.patches | length' "$MANIFEST")"
(( patch_count == 20 )) || { echo "Expected 20 pinned Termux QEMU patches, found $patch_count" >&2; exit 1; }
declare -A manifest_patch_paths=()
while IFS=$'\t' read -r patch_path patch_sha256; do
  patch_path="${patch_path//$'\r'/}"
  patch_sha256="${patch_sha256//$'\r'/}"
  [[ "$patch_path" == packages/qemu-system-x86-64-headless/*.patch ]] || { echo "Unsafe patch path: $patch_path" >&2; exit 1; }
  [[ "$patch_path" != *".."* ]] || { echo "Unsafe patch path: $patch_path" >&2; exit 1; }
  [[ "$patch_sha256" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid patch SHA-256 for $patch_path" >&2; exit 1; }
  [[ -z "${manifest_patch_paths[$patch_path]:-}" ]] || { echo "Duplicate pinned Termux patch: $patch_path" >&2; exit 1; }
  manifest_patch_paths["$patch_path"]="$patch_sha256"
done < <(jq -r '.termuxPackages.patches[] | [.path, .sha256] | @tsv' "$MANIFEST")

if [[ "$verify_only" == true ]]; then
  echo "Native QEMU manifest verified: QEMU $qemu_version, Termux $termux_revision, $patch_count patches"
  exit 0
fi

[[ "$host_abi" == "arm64-v8a" || "$host_abi" == "x86_64" ]] || usage
[[ -n "$output" ]] || usage
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v docker >/dev/null || { echo "Docker is required for the Termux package builder" >&2; exit 1; }

termux_arch="aarch64"
[[ "$host_abi" == "x86_64" ]] && termux_arch="x86_64"
work_parent="${OPENVM_QEMU_WORK_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}}"
[[ -d "$work_parent" ]] || { echo "The native QEMU work parent must already exist: $work_parent" >&2; exit 1; }
work_parent="$(realpath -m "$work_parent")"
[[ "$work_parent" != "/" ]] || { echo "Refusing the filesystem root as the native QEMU work parent" >&2; exit 1; }
work_root="$(mktemp -d "$work_parent/openvm-qemu-build.XXXXXX")"
work_marker="$work_root/.openvm-native-qemu-work-root"
printf 'openvm-native-qemu-work-root-v1\n' > "$work_marker"
keep_work="${OPENVM_QEMU_KEEP_WORK:-false}"
[[ "$keep_work" == true || "$keep_work" == false ]] || { echo "OPENVM_QEMU_KEEP_WORK must be true or false" >&2; exit 1; }
container_name="openvm-termux-builder-${host_abi//[^a-zA-Z0-9_.-]/-}-$$"
termux_dir="$work_root/termux-packages"
prefix_dir="$work_root/termux-prefix"
source_archive="$work_root/qemu-${qemu_version}.tar.xz"
output_dir="$(realpath -m "$output")"
output_parent="$(dirname "$output_dir")"
[[ "$output_parent" != "/" ]] || { echo "Refusing the filesystem root as the native QEMU output parent" >&2; exit 1; }
mkdir -p "$output_parent"
cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  if [[ "$keep_work" != true && -f "$work_marker" && "$(<"$work_marker")" == "openvm-native-qemu-work-root-v1" ]]; then
    rm -rf -- "$work_root"
  fi
}
trap cleanup EXIT

mkdir -p "$work_root"
curl --fail --location --retry 3 --silent --show-error "$source_url" --output "$source_archive"
printf '%s  %s\n' "$source_sha256" "$source_archive" | sha256sum --check --status

git init "$termux_dir" >/dev/null
git -C "$termux_dir" remote add origin "$termux_repo"
git -C "$termux_dir" fetch --depth 1 origin "$termux_revision"
git -C "$termux_dir" checkout --detach FETCH_HEAD >/dev/null
actual_termux_revision="$(git -C "$termux_dir" rev-parse HEAD)"
[[ "$actual_termux_revision" == "$termux_revision" ]] || { echo "Termux revision verification failed" >&2; exit 1; }
[[ -f "$termux_dir/$termux_recipe" ]] || { echo "Pinned Termux recipe is missing" >&2; exit 1; }
recipe_sha256="$(sha256sum "$termux_dir/$termux_recipe" | awk '{print $1}')"
manifest_patch_list="$work_root/manifest-patches.txt"
termux_patch_list="$work_root/termux-patches.txt"
termux_patch_dir="${termux_recipe%/build.sh}"
termux_recipe_dir="$termux_patch_dir"
jq -r '.termuxPackages.patches[] | [.path, .sha256] | @tsv' "$MANIFEST" | tr -d '\r' | sort > "$manifest_patch_list"
git -C "$termux_dir" ls-tree -r --name-only HEAD -- "$termux_patch_dir" | grep -E '\.patch$' | sort > "$termux_patch_list"
cut -f1 "$manifest_patch_list" | diff -u "$termux_patch_list" - || {
  echo "The pinned Termux patch manifest does not equal the complete patch set in the pinned revision" >&2
  exit 1
}
while IFS=$'\t' read -r patch_path patch_sha256; do
  patch_path="${patch_path//$'\r'/}"
  patch_sha256="${patch_sha256//$'\r'/}"
  patch_file="$termux_dir/$patch_path"
  [[ -f "$patch_file" ]] || { echo "Pinned Termux patch is missing: $patch_path" >&2; exit 1; }
  printf '%s  %s\n' "$patch_sha256" "$patch_file" | sha256sum --check --status
done < <(jq -r '.termuxPackages.patches[] | [.path, .sha256] | @tsv' "$MANIFEST")

docker pull "$builder_image"
builder_digest="$(docker image inspect "$builder_image" --format '{{range .RepoDigests}}{{println .}}{{end}}' 2>/dev/null | grep -Fx "$builder_image" || true)"
[[ "$builder_digest" == "$builder_image" ]] || { echo "The pulled Termux builder image did not match the manifest digest pin" >&2; exit 1; }
patch_manifest_sha256="$(sha256sum "$manifest_patch_list" | awk '{print $1}')"
echo "Using Termux builder image: $builder_digest"

(
  cd "$termux_dir"
  TERMUX_BUILDER_IMAGE_NAME="$builder_image" \
  CONTAINER_NAME="$container_name" \
  TERMUX_DOCKER_RUN_EXTRA_ARGS="--env TERMUX_APP__PACKAGE_NAME=com.termux" \
    ./scripts/run-docker.sh bash -lc "./build-package.sh -I -C -a '$termux_arch' '$termux_recipe_dir'"
)

mkdir -p "$prefix_dir"
docker cp "$container_name:/data/data/com.termux/files/usr/." "$prefix_dir/"
bash "$ROOT_DIR/native/qemu/collect-runtime.sh" "$host_abi" "$prefix_dir" "$output_dir" "$required_guest_page_size"
printf '{\n  "schemaVersion": 1,\n  "builderImage": "%s",\n  "termuxRevision": "%s",\n  "termuxRecipe": "%s",\n  "termuxRecipeSha256": "%s",\n  "termuxPatchManifestSha256": "%s",\n  "qemuVersion": "%s",\n  "qemuSourceSha256": "%s",\n  "requiredNativeApi": %s,\n  "requiredPageSizeBytes": %s\n}\n' \
  "$builder_digest" "$actual_termux_revision" "$termux_recipe" "$recipe_sha256" "$patch_manifest_sha256" \
  "$qemu_version" "$source_sha256" "$minimum_native_api" "$required_guest_page_size" \
  > "$output_dir/build-provenance.json"
echo "Android QEMU runtime build completed: $output_dir"
