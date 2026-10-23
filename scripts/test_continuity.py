"""Continuity orchestration must actually alternate compiled target writers."""
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


class ContinuityTests(unittest.TestCase):
    def test_alternates_target_processes(self):
        from verify_csv_continuity import run_writers
        with tempfile.TemporaryDirectory() as folder, patch('subprocess.run') as run, \
                patch('verify_csv_continuity.validate_step', return_value=b'validated'):
            run_writers(Path('/tmp/build262'), Path('/tmp/build263'), Path(folder))
            self.assertEqual(run.call_count, 3)
            commands = [call.args[0] for call in run.call_args_list]
            self.assertIn('/tmp/build262', commands[0][2])
            self.assertIn('/tmp/build263', commands[1][2])
            self.assertIn('/tmp/build262', commands[2][2])
            self.assertTrue(all(call.kwargs['check'] for call in run.call_args_list))
