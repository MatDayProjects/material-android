from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[2]
MANIFEST = ROOT / "native/qemu/qemu-build.json"
COMMAND_BUILDER = ROOT / "app/src/main/java/org/openvm/app/backend/QemuRuntimeController.kt"


class QemuRuntimeContractTests(unittest.TestCase):
    def test_direct_kernel_boot_firmware_is_allowlisted_once(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        files = manifest["packaging"]["qemuDataFiles"]

        self.assertEqual(len(files), len(set(files)))
        self.assertEqual(1, files.count("linuxboot_dma.bin"))
        self.assertTrue(all(Path(name).name == name for name in files))

    def test_runtime_command_explicitly_disables_unimplemented_networking(self) -> None:
        source = COMMAND_BUILDER.read_text(encoding="utf-8")

        self.assertIn('"-nic", "none"', source)


if __name__ == "__main__":
    unittest.main()
