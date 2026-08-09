#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <android-abi> <termux-prefix> <output-directory> <required-page-size-bytes>" >&2
  exit 2
}

[[ $# -eq 4 ]] || usage
abi="$1"
prefix="$(realpath "$2")"
output="$3"
required_page_size="$4"

case "$abi" in
  arm64-v8a|x86_64) ;;
  *) echo "Unsupported Android ABI: $abi" >&2; exit 1 ;;
esac

[[ -d "$prefix" ]] || { echo "Termux prefix does not exist: $prefix" >&2; exit 1; }
command -v readelf >/dev/null || { echo "binutils readelf is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
[[ "$required_page_size" =~ ^[0-9]+$ && "$required_page_size" -gt 0 ]] || { echo "Required page size must be a positive integer" >&2; exit 1; }

output="$(realpath -m "$output")"
[[ -n "$output" && "$output" != "/" ]] || { echo "Refusing an empty or root output path" >&2; exit 1; }
if [[ -e "$output" || -L "$output" ]]; then
  echo "Refusing to replace an existing runtime output directory: $output" >&2
  exit 1
fi
output_parent="$(dirname "$output")"
[[ -d "$output_parent" ]] || { echo "Runtime output parent does not exist: $output_parent" >&2; exit 1; }
mkdir -p "$output/lib" "$output/share"

expected_alignment="0x$(printf '%x' "$required_page_size")"
expected_machine="AArch64"
[[ "$abi" == "x86_64" ]] && expected_machine="Advanced Micro Devices X86-64"

validate_elf() {
  local file="$1"
  local kind="$2"
  local header program_headers alignments alignment interpreter
  header="$(readelf -h "$file" 2>&1)" || {
    printf 'readelf -h failed for %s (%s):\n%s\n' "$file" "$kind" "$header" >&2
    return 1
  }
  grep -Eq '^ *Class: +ELF64$' <<<"$header" || {
    echo "Runtime $kind is not an ELF64 file: $file" >&2
    return 1
  }
  if ! grep -Fq "Machine:" <<<"$header" || ! grep -Fq "$expected_machine" <<<"$header"; then
    echo "Runtime $kind has the wrong machine for $abi: $file" >&2
    return 1
  fi
  program_headers="$(readelf -lW "$file" 2>&1)" || {
    printf 'readelf -l failed for %s (%s):\n%s\n' "$file" "$kind" "$program_headers" >&2
    return 1
  }
  alignments="$(awk '$1 == "LOAD" {print $NF}' <<<"$program_headers")"
  [[ -n "$alignments" ]] || { echo "Runtime $kind has no loadable ELF segments: $file" >&2; return 1; }
  while IFS= read -r alignment; do
    [[ "$alignment" == "$expected_alignment" ]] || {
      echo "Runtime $kind has $alignment load alignment; expected $expected_alignment: $file" >&2
      return 1
    }
  done <<<"$alignments"
  if [[ "$kind" == executable ]]; then
    interpreter="$(sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p' <<<"$program_headers" | head -n1)"
    [[ "$interpreter" == /system/bin/linker64 ]] || {
      echo "Runtime executable has an unexpected Android interpreter ($interpreter): $file" >&2
      return 1
    }
  fi
}

declare -a qemu_guests=(aarch64 x86_64)
declare -a queue=()
declare -A queued=()
declare -A copied=()

copy_library() {
  local name="$1"
  local source="$prefix/lib/$name"
  [[ -e "$source" || -L "$source" ]] || return 1

  local resolved
  resolved="$(readlink -f "$source")"
  [[ "$resolved" == "$prefix/lib/"* && -f "$resolved" ]] || {
    echo "Library leaves the Termux prefix: $name -> $resolved" >&2
    return 1
  }
  validate_elf "$resolved" library || return 1

  if [[ -z "${copied[$name]:-}" ]]; then
    cp -- "$resolved" "$output/lib/$name"
    chmod 0644 "$output/lib/$name"
    copied["$name"]=1
  fi
  local resolved_name
  resolved_name="$(basename "$resolved")"
  if [[ -z "${copied[$resolved_name]:-}" ]]; then
    cp -- "$resolved" "$output/lib/$resolved_name"
    chmod 0644 "$output/lib/$resolved_name"
    copied["$resolved_name"]=1
  fi
  if [[ -z "${queued[$resolved]:-}" ]]; then
    queue+=("$resolved")
    queued["$resolved"]=1
  fi
}

for guest in "${qemu_guests[@]}"; do
  source="$prefix/bin/qemu-system-$guest"
  [[ -f "$source" ]] || { echo "QEMU guest executable is missing: $source" >&2; exit 1; }
  validate_elf "$source" executable || exit 1
  destination="$output/libopenvm-qemu-$guest.so"
  cp -- "$source" "$destination"
  chmod 0755 "$destination"
  queue+=("$source")
  queued["$source"]=1
done

while ((${#queue[@]})); do
  file="${queue[0]}"
  queue=("${queue[@]:1}")
  if ! dynamic_section="$(readelf -d "$file" 2>&1)"; then
    printf 'readelf -d failed for %s:\n%s\n' "$file" "$dynamic_section" >&2
    exit 1
  fi
  while IFS= read -r dependency; do
    [[ -n "$dependency" ]] || continue
    if [[ -e "$prefix/lib/$dependency" || -L "$prefix/lib/$dependency" ]]; then
      copy_library "$dependency" || exit 1
    elif [[ "$dependency" == "libc.so" || "$dependency" == "libm.so" || "$dependency" == "libdl.so" || "$dependency" == "liblog.so" || "$dependency" == "libandroid.so" || "$dependency" == "libOpenSLES.so" ]]; then
      : # Provided by Android's linker namespace on the target device.
    else
      echo "Required runtime library is absent from the Termux prefix: $dependency" >&2
      exit 1
    fi
  done < <(awk -F'[][]' '/NEEDED/ {print $2}' <<<"$dynamic_section")
done

[[ -d "$prefix/share/qemu" ]] || { echo "QEMU data directory is missing from the Termux runtime prefix" >&2; exit 1; }
qemu_data_source="$prefix/share/qemu"
qemu_data_output="$output/share/qemu"
qemu_data_file_count=0
mkdir -p "$qemu_data_output"
while IFS= read -r -d '' data_file; do
  relative_path="${data_file#"$qemu_data_source/"}"
  destination="$qemu_data_output/$relative_path"
  mkdir -p "$(dirname "$destination")"
  cp -a -- "$data_file" "$destination"
  qemu_data_file_count=$((qemu_data_file_count + 1))
done < <(
  find "$qemu_data_source" -type f \
    ! -path "$qemu_data_source/docs/*" \
    ! -path "$qemu_data_source/keymaps/*" \
    ! -path "$qemu_data_source/man/*" \
    -print0
)
(( qemu_data_file_count > 0 )) || { echo "QEMU data directory has no runtime files after excluding documentation, keymaps, and man pages" >&2; exit 1; }
data_size="$(du -sb "$qemu_data_output" | awk '{print $1}')"
(( data_size <= 67108864 )) || { echo "QEMU runtime data exceeds 64 MiB after excluding documentation, keymaps, and man pages" >&2; exit 1; }

sha256_file() { sha256sum "$1" | awk '{print $1}'; }

{
  printf '{\n  "schemaVersion": 1,\n  "androidAbi": "%s",\n  "binaries": {\n' "$abi"
  for index in "${!qemu_guests[@]}"; do
    guest="${qemu_guests[$index]}"
    file="$output/libopenvm-qemu-$guest.so"
    comma=','
    (( index == ${#qemu_guests[@]} - 1 )) && comma=''
    printf '    "%s": {"path": "libopenvm-qemu-%s.so", "sizeBytes": %s, "sha256": "%s"}%s\n' \
      "$guest" "$guest" "$(stat -c '%s' "$file")" "$(sha256_file "$file")" "$comma"
  done
  printf '  },\n  "libraryCount": %s,\n  "qemuDataFileCount": %s,\n  "qemuDataBytes": %s,\n  "hasQemuDataDirectory": %s,\n  "excludedDataDirectories": ["docs", "keymaps", "man"]\n}\n' \
    "$(find "$output/lib" -maxdepth 1 -type f -name '*.so*' ! -name 'libopenvm-qemu-*.so' | wc -l)" \
    "$qemu_data_file_count" \
    "$data_size" \
    "$([[ -d "$output/share/qemu" ]] && echo true || echo false)"
} > "$output/runtime.json"

echo "Collected Android QEMU runtime for $abi at $output"
