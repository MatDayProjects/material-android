from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[2]
WORKFLOW_PATHS = {
    "android-ci": ROOT / ".github/workflows/android-ci.yml",
    "android-native-qemu": ROOT / ".github/workflows/android-native-qemu.yml",
    "android-release": ROOT / ".github/workflows/android-release.yml",
}
DEBUG_PACKAGE_PATHS = {
    "app/build/outputs/apk/debug",
    "app/build/outputs/apk/androidTest/debug",
}
RAW_APK_UPLOAD = re.compile(
    r"^\s+(?:\*\*/|app/)build/outputs/apk/.+\*\.apk\s*$",
    re.MULTILINE,
)
SIGNING_COMMAND = re.compile(
    r"(?:"
    r"\bapksigner[ \t]+sign\b|"
    r"\bkeytool[ \t]+-(?:genkey|genkeypair|importkeystore)\b|"
    r"(?:^[ \t]*|\$\([ \t]*)jarsigner[ \t]+(?!-verify\b)"
    r")",
    re.IGNORECASE | re.MULTILINE,
)


class WorkflowUnsignedContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflows = {
            name: path.read_text(encoding="utf-8") for name, path in WORKFLOW_PATHS.items()
        }

    def test_every_android_workflow_invokes_the_committed_verifier(self) -> None:
        marker = "python3 scripts/verify_unsigned_android_artifacts.py"
        for name, workflow in self.workflows.items():
            with self.subTest(workflow=name):
                self.assertIn(marker, workflow)

    def test_debug_workflows_require_app_and_instrumentation_directories(self) -> None:
        for name, workflow in self.workflows.items():
            with self.subTest(workflow=name):
                for path in DEBUG_PACKAGE_PATHS:
                    self.assertIn(path, workflow)
                self.assertIn("Expected exactly one debug app APK", workflow)

    def test_raw_build_output_apks_are_never_uploaded(self) -> None:
        for name, workflow in self.workflows.items():
            with self.subTest(workflow=name):
                self.assertIsNone(RAW_APK_UPLOAD.search(workflow))

    def test_uploaded_packages_come_from_verified_staging(self) -> None:
        expected_markers = {
            "android-ci": "openvm-verified-debug-apks",
            "android-native-qemu": "openvm-verified-native-apks",
            "android-release": "openvm-verified-release-debug-apks",
        }
        for name, marker in expected_markers.items():
            with self.subTest(workflow=name):
                self.assertIn(marker, self.workflows[name])
                self.assertIn("ci-context.txt", self.workflows[name])

    def test_release_uses_the_pinned_bundletool_validator(self) -> None:
        workflow = self.workflows["android-release"]
        self.assertIn('bundletool_version="1.18.3"', workflow)
        self.assertIn(
            'bundletool_sha256="a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"',
            workflow,
        )
        self.assertIn('java -jar "$BUNDLETOOL_JAR" validate', workflow)

    def test_no_workflow_invokes_a_signing_command(self) -> None:
        for name, workflow in self.workflows.items():
            with self.subTest(workflow=name):
                self.assertIsNone(SIGNING_COMMAND.search(workflow))

    def test_signing_command_detector_rejects_real_invocation_forms(self) -> None:
        rejected = (
            "apksigner sign --ks release.jks app.apk",
            "jarsigner -keystore release.jks app.aab alias",
            'output="$(jarsigner app.aab alias)"',
            "keytool -genkeypair -keystore release.jks",
        )
        accepted = (
            "apksigner verify --verbose app.apk",
            'output="$(jarsigner -verify -strict app.aab)"',
            "command -v jarsigner",
        )
        for command in rejected:
            with self.subTest(rejected=command):
                self.assertIsNotNone(SIGNING_COMMAND.search(command))
        for command in accepted:
            with self.subTest(accepted=command):
                self.assertIsNone(SIGNING_COMMAND.search(command))


if __name__ == "__main__":
    unittest.main()
