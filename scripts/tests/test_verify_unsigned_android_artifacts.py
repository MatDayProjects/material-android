from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT_PATH = Path(__file__).parents[1] / "verify_unsigned_android_artifacts.py"
SPEC = importlib.util.spec_from_file_location("unsigned_verifier", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)


def add_apk_signing_block(path: Path) -> None:
    payload = bytearray(path.read_bytes())
    eocd_offset = payload.rfind(verifier.EOCD_MAGIC)
    if eocd_offset < 0:
        raise AssertionError("test ZIP has no EOCD")
    central_directory_offset = struct.unpack_from("<I", payload, eocd_offset + 16)[0]
    block_size = 24
    block = (
        struct.pack("<Q", block_size)
        + struct.pack("<Q", block_size)
        + verifier.APK_SIGNING_BLOCK_MAGIC
    )
    payload[central_directory_offset:central_directory_offset] = block
    struct.pack_into(
        "<I",
        payload,
        eocd_offset + len(block) + 16,
        central_directory_offset + len(block),
    )
    path.write_bytes(payload)


class UnsignedArtifactVerifierTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_accepts_valid_unsigned_apk(self) -> None:
        artifact = self.root / "app.apk"
        write_zip(artifact, {"AndroidManifest.xml": b"manifest", "classes.dex": b"dex"})
        verifier.verify_artifact(artifact)

    def test_accepts_structurally_unsigned_aab_container(self) -> None:
        artifact = self.root / "app.aab"
        write_zip(
            artifact,
            {
                "BundleConfig.pb": b"config",
                "base/manifest/AndroidManifest.xml": b"manifest",
            },
        )
        verifier.verify_artifact(artifact)

    def test_rejects_v1_signature_metadata_case_insensitively(self) -> None:
        artifact = self.root / "signed.apk"
        write_zip(
            artifact,
            {
                "AndroidManifest.xml": b"manifest",
                "meta-inf/cert.sf": b"signature",
            },
        )
        with self.assertRaisesRegex(verifier.VerificationError, "signature metadata"):
            verifier.verify_artifact(artifact)

    def test_rejects_apk_signing_block(self) -> None:
        artifact = self.root / "signed-v2.apk"
        write_zip(artifact, {"AndroidManifest.xml": b"manifest"})
        add_apk_signing_block(artifact)
        with self.assertRaisesRegex(verifier.VerificationError, "APK Signing Block"):
            verifier.verify_artifact(artifact)

    def test_rejects_signing_block_in_aab_container(self) -> None:
        artifact = self.root / "signed.aab"
        write_zip(
            artifact,
            {
                "BundleConfig.pb": b"config",
                "base/manifest/AndroidManifest.xml": b"manifest",
            },
        )
        add_apk_signing_block(artifact)
        with self.assertRaisesRegex(verifier.VerificationError, "APK Signing Block"):
            verifier.verify_artifact(artifact)

    def test_rejects_detached_idsig(self) -> None:
        artifact = self.root / "app.apk"
        write_zip(artifact, {"AndroidManifest.xml": b"manifest"})
        Path(f"{artifact}.idsig").write_bytes(b"detached signature")
        with self.assertRaisesRegex(verifier.VerificationError, "idsig"):
            verifier.verify_artifact(artifact)

    def test_rejects_invalid_zip(self) -> None:
        artifact = self.root / "broken.apk"
        artifact.write_bytes(b"not a zip")
        with self.assertRaisesRegex(verifier.VerificationError, "ZIP"):
            verifier.verify_artifact(artifact)

    def test_rejects_generated_signing_material(self) -> None:
        material = self.root / "debug.keystore"
        material.write_bytes(b"test fixture")
        self.assertEqual(verifier.find_signing_material([self.root]), [material.resolve()])

    def test_rejects_symlinked_artifact_file(self) -> None:
        artifact = self.root / "outside.apk"
        write_zip(artifact, {"AndroidManifest.xml": b"manifest"})
        link = self.root / "linked.apk"
        try:
            link.symlink_to(artifact)
        except OSError as error:
            self.skipTest(f"symbolic links are unavailable: {error}")
        with self.assertRaisesRegex(verifier.VerificationError, "symbolic-link"):
            verifier.find_artifacts([link])

    def test_rejects_symlinked_signing_material_directory(self) -> None:
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "release.jks").write_bytes(b"test fixture")
        scan_root = self.root / "scan"
        scan_root.mkdir()
        link = scan_root / "linked"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"symbolic links are unavailable: {error}")
        with self.assertRaisesRegex(verifier.VerificationError, "symbolic-link directories"):
            verifier.find_signing_material([scan_root])


if __name__ == "__main__":
    unittest.main()
