"""CI must require both builds and continuity before release."""
from pathlib import Path
import unittest


class WorkflowTests(unittest.TestCase):
    def test_matrix_and_release_gate(self):
        workflow = Path('.github/workflows/release.yml').read_text()
        self.assertIn("minecraft: ['26.2', '26.3']", workflow)
        self.assertIn('needs: [version, build, continuity]', workflow)
        self.assertIn('verify_csv_continuity.py', workflow)
        self.assertIn('verify_mod_jar.py --pair release', workflow)
        self.assertIn('--max-workers=1', workflow)
        self.assertIn('chestlogger-csv-${{ matrix.minecraft }}', workflow)
