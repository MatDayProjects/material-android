#!/usr/bin/env python3
"""Fail closed unless Android build artifacts are valid and unsigned.

The verifier checks JAR/v1 metadata, APK v2/v3 signing blocks, detached v4
signature files, basic ZIP integrity, and accidentally generated signing files.
It uses only the Python standard library so fresh CI runners can execute it.
"""

from __future__ import annotations

import argparse
import os
import re
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath
from typing import Iterable, Sequence


APK_SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"
EOCD_MAGIC = b"PK\x05\x06"
CENTRAL_DIRECTORY_MAGIC = b"PK\x01\x02"
MAX_EOCD_SIZE = 22 + 65_535
MAX_ARCHIVE_ENTRIES = 200_000
MAX_TOTAL_UNCOMPRESSED_BYTES = 16 * 1024 * 1024 * 1024
JAR_SIGNATURE_ENTRY = re.compile(
    r"^META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]*)$",
    re.IGNORECASE,
)
SIGNING_MATERIAL_SUFFIXES = {
    ".idsig",
    ".jks",
    ".key",
    ".keystore",
    ".p12",
    ".pfx",
    ".pem",
}
SIGNING_MATERIAL_NAMES = {"debug.keystore"}
SKIPPED_SCAN_DIRECTORIES = {".git"}


class VerificationError(RuntimeError):
    """Raised when an artifact cannot be proven valid and unsigned."""


def _read_eocd(path: Path) -> tuple[int, int]:
    file_size = path.stat().st_size
    if file_size < 22:
        raise VerificationError(f"{path}: file is too small to be a ZIP archive")

    tail_size = min(file_size, MAX_EOCD_SIZE)
    with path.open("rb") as stream:
        stream.seek(file_size - tail_size)
        tail = stream.read(tail_size)

    search_end = len(tail)
    while True:
        relative_offset = tail.rfind(EOCD_MAGIC, 0, search_end)
        if relative_offset < 0:
            raise VerificationError(f"{path}: ZIP end-of-central-directory record is missing")
        if relative_offset + 22 <= len(tail):
            comment_length = struct.unpack_from("<H", tail, relative_offset + 20)[0]
            if relative_offset + 22 + comment_length == len(tail):
                break
        search_end = relative_offset

    eocd_offset = file_size - tail_size + relative_offset
    (
        disk_number,
        central_directory_disk,
        entries_on_disk,
        total_entries,
        central_directory_size,
        central_directory_offset,
    ) = struct.unpack_from("<HHHHII", tail, relative_offset + 4)

    if disk_number != 0 or central_directory_disk != 0 or entries_on_disk != total_entries:
        raise VerificationError(f"{path}: multi-disk ZIP archives are not supported")
    if (
        total_entries == 0xFFFF
        or central_directory_size == 0xFFFFFFFF
        or central_directory_offset == 0xFFFFFFFF
    ):
        raise VerificationError(f"{path}: ZIP64 Android artifacts are not supported")
    if central_directory_offset + central_directory_size != eocd_offset:
        raise VerificationError(f"{path}: central-directory bounds are inconsistent")
    if total_entries and central_directory_offset + 4 <= file_size:
        with path.open("rb") as stream:
            stream.seek(central_directory_offset)
            if stream.read(4) != CENTRAL_DIRECTORY_MAGIC:
                raise VerificationError(f"{path}: central-directory signature is invalid")
    return central_directory_offset, total_entries


def _reject_apk_signing_block(path: Path, central_directory_offset: int) -> None:
    if central_directory_offset < 24:
        return
    with path.open("rb") as stream:
        stream.seek(central_directory_offset - 24)
        footer = stream.read(24)

    if footer[8:] != APK_SIGNING_BLOCK_MAGIC:
        return

    footer_size = struct.unpack_from("<Q", footer, 0)[0]
    total_block_size = footer_size + 8
    if footer_size < 24 or total_block_size > central_directory_offset:
        raise VerificationError(f"{path}: malformed APK Signing Block is present")

    block_start = central_directory_offset - total_block_size
    with path.open("rb") as stream:
        stream.seek(block_start)
        header_size_bytes = stream.read(8)
    if len(header_size_bytes) != 8:
        raise VerificationError(f"{path}: truncated APK Signing Block is present")
    header_size = struct.unpack("<Q", header_size_bytes)[0]
    if header_size != footer_size:
        raise VerificationError(f"{path}: inconsistent APK Signing Block is present")
    raise VerificationError(f"{path}: APK Signing Block is present")


def _validate_member_path(path: Path, member_name: str) -> None:
    member = PurePosixPath(member_name)
    if (
        not member_name
        or member_name.startswith(("/", "\\"))
        or "\\" in member_name
        or ".." in member.parts
    ):
        raise VerificationError(f"{path}: unsafe archive member path {member_name!r}")


def verify_artifact(path: Path) -> None:
    if path.is_symlink():
        raise VerificationError(f"{path}: symbolic-link artifacts are not allowed")
    path = path.resolve()
    if not path.is_file():
        raise VerificationError(f"{path}: artifact does not exist or is not a file")
    artifact_type = path.suffix.lower()
    if artifact_type not in {".apk", ".aab"}:
        raise VerificationError(f"{path}: expected an .apk or .aab artifact")
    if Path(f"{path}.idsig").exists() or path.with_suffix(".idsig").exists():
        raise VerificationError(f"{path}: detached APK signature (.idsig) is present")

    central_directory_offset, expected_entries = _read_eocd(path)
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            if len(members) != expected_entries:
                raise VerificationError(f"{path}: ZIP entry count is inconsistent")
            if len(members) > MAX_ARCHIVE_ENTRIES:
                raise VerificationError(f"{path}: archive has too many entries")

            names: set[str] = set()
            total_uncompressed = 0
            signature_entries: list[str] = []
            for member in members:
                _validate_member_path(path, member.filename)
                if member.filename in names:
                    raise VerificationError(
                        f"{path}: duplicate archive member {member.filename!r}"
                    )
                names.add(member.filename)
                if member.flag_bits & 0x1:
                    raise VerificationError(
                        f"{path}: encrypted archive member {member.filename!r}"
                    )
                total_uncompressed += member.file_size
                if total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
                    raise VerificationError(f"{path}: uncompressed archive size is excessive")
                if JAR_SIGNATURE_ENTRY.fullmatch(member.filename):
                    signature_entries.append(member.filename)

            if signature_entries:
                joined = ", ".join(sorted(signature_entries, key=str.casefold))
                raise VerificationError(f"{path}: JAR signature metadata is present: {joined}")

            if artifact_type == ".apk" and "AndroidManifest.xml" not in names:
                raise VerificationError(f"{path}: AndroidManifest.xml is missing")
            if artifact_type == ".aab":
                required = {"BundleConfig.pb", "base/manifest/AndroidManifest.xml"}
                missing = sorted(required - names)
                if missing:
                    raise VerificationError(
                        f"{path}: required Android App Bundle entries are missing: "
                        + ", ".join(missing)
                    )

            corrupt_member = archive.testzip()
            if corrupt_member is not None:
                raise VerificationError(f"{path}: CRC check failed for {corrupt_member!r}")
    except zipfile.BadZipFile as error:
        raise VerificationError(f"{path}: invalid ZIP structure: {error}") from error

    _reject_apk_signing_block(path, central_directory_offset)


def find_artifacts(inputs: Sequence[Path]) -> list[Path]:
    artifacts: list[Path] = []
    detached_signatures: list[Path] = []
    for supplied in inputs:
        if supplied.is_symlink():
            raise VerificationError(f"{supplied}: symbolic-link inputs are not allowed")
        path = supplied.resolve()
        if path.is_dir():
            for candidate in path.rglob("*"):
                if candidate.is_symlink():
                    raise VerificationError(
                        f"{candidate}: symbolic links are not allowed below artifact roots"
                    )
                if not candidate.is_file():
                    continue
                suffix = candidate.suffix.lower()
                if suffix in {".apk", ".aab"}:
                    artifacts.append(candidate)
                elif suffix == ".idsig":
                    detached_signatures.append(candidate)
        elif path.is_file():
            if path.suffix.lower() == ".idsig":
                detached_signatures.append(path)
            else:
                artifacts.append(path)
        else:
            raise VerificationError(f"{path}: input does not exist")

    if detached_signatures:
        names = ", ".join(str(path) for path in sorted(detached_signatures))
        raise VerificationError(f"detached APK signature files are present: {names}")
    unique = sorted(set(artifacts), key=lambda item: str(item).casefold())
    if not unique:
        raise VerificationError("no APK or AAB artifacts were found")
    return unique


def find_signing_material(roots: Iterable[Path]) -> list[Path]:
    matches: list[Path] = []
    for supplied_root in roots:
        if supplied_root.is_symlink():
            raise VerificationError(
                f"{supplied_root}: symbolic-link signing-material roots are not allowed"
            )
        root = supplied_root.resolve()
        if not root.exists():
            raise VerificationError(f"{root}: signing-material scan root does not exist")
        if root.is_file():
            candidates = [root]
        else:
            candidates = []
            for directory, directory_names, file_names in os.walk(root, followlinks=False):
                directory_path = Path(directory)
                symlinked_directories = [
                    directory_path / name
                    for name in directory_names
                    if (directory_path / name).is_symlink()
                ]
                if symlinked_directories:
                    names = ", ".join(str(path) for path in symlinked_directories)
                    raise VerificationError(
                        f"symbolic-link directories prevent a bounded signing-material scan: {names}"
                    )
                directory_names[:] = [
                    name for name in directory_names if name not in SKIPPED_SCAN_DIRECTORIES
                ]
                candidates.extend(Path(directory, name) for name in file_names)
        for candidate in candidates:
            if candidate.is_symlink():
                raise VerificationError(
                    f"{candidate}: symbolic-link files are not allowed in signing-material scans"
                )
            lowered_name = candidate.name.casefold()
            if (
                lowered_name in SIGNING_MATERIAL_NAMES
                or candidate.suffix.casefold() in SIGNING_MATERIAL_SUFFIXES
            ):
                matches.append(candidate.resolve())
    return sorted(set(matches), key=lambda item: str(item).casefold())


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "artifacts",
        nargs="+",
        type=Path,
        help="APK/AAB files or directories containing artifacts",
    )
    parser.add_argument(
        "--forbid-signing-material-root",
        action="append",
        default=[],
        type=Path,
        metavar="PATH",
        help="recursively reject signing-material filenames below PATH",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        material = find_signing_material(args.forbid_signing_material_root)
        if material:
            names = ", ".join(str(path) for path in material)
            raise VerificationError(f"signing material is present: {names}")
        artifacts = find_artifacts(args.artifacts)
        for artifact in artifacts:
            verify_artifact(artifact)
            print(f"Verified structurally valid unsigned Android container: {artifact}")
        print(f"Verified {len(artifacts)} unsigned Android container(s).")
        return 0
    except (OSError, VerificationError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
