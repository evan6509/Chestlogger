"""Exercise the real Gradle selector, resource expansion, and paired packaging."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import test_release
import shutil
import verify_mod_jar

ROOT = Path(__file__).resolve().parents[1]


class TargetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='chestlogger-target-tests-')
        cls.base = Path(os.environ.get('CHESTLOGGER_TEST_WORKSPACES', cls.temporary.name)).resolve()
        for target in ('26.2', '26.3'):
            workspace = cls.base / target
            if not workspace.exists():
                shutil.copytree(ROOT, workspace, ignore=shutil.ignore_patterns('.git', '.gradle', 'build', '__pycache__'))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def gradle(self, *args):
        target = next((arg.split('=', 1)[1] for arg in args if arg.startswith('-Pminecraft_target=')), '26.2')
        workspace = self.base / (target if target in ('26.2', '26.3') else '26.2')
        env = dict(os.environ, CHESTLOGGER_VERSION='1.0.0', GRADLE_USER_HOME=str(self.base / f'home-{workspace.name}'))
        return subprocess.run([str(workspace / 'gradlew'), '--no-daemon', '--max-workers=1',
                               '--project-cache-dir', str(self.base / f'cache-{workspace.name}'),
                               '--console=plain', *args], cwd=workspace, env=env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_selector_and_expanded_metadata(self):
        for target, api in [('26.2', '>=0.154.2'), ('26.3', '>=0.164.0')]:
            result = self.gradle(f'-Pminecraft_target={target}', 'processResources')
            self.assertEqual(result.returncode, 0, result.stdout)
            metadata = json.loads((self.base / target / f'build/{target}/resources/main/fabric.mod.json').read_text())
            self.assertEqual(metadata['depends'], dict(minecraft=target, java='>=25',
                             fabricloader='>=0.19.3', **{'fabric-api': api}))
            self.assertEqual(metadata['version'], f'1.0.0+mc{target}')
        result = self.gradle('targetInfo')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('target=26.2', result.stdout)

    def test_invalid_target_and_override_rejected(self):
        for args in [('-Pminecraft_target=26.4',), ('-Pminecraft_target=26.3', '-Pfabric_version=0.154.2+26.2'),
                     ('-Pminecraft_version=26.3',)]:
            result = self.gradle(*args, 'help')
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn('target', result.stdout.lower())


class PairedJarTests(unittest.TestCase):
    def test_release_requires_exact_pair(self):
        with tempfile.TemporaryDirectory() as folder:
            test_release.JarTests().fixture(folder)
            with self.assertRaises(ValueError):
                verify_mod_jar.verify_pair(folder, '1.0.1')

            test_release.JarTests().fixture(folder, embedded='1.0.1+mc26.3', target='26.3')
            self.assertEqual(len(verify_mod_jar.verify_pair(folder, '1.0.1')), 2)
            with self.assertRaises(ValueError):
                verify_mod_jar.verify(folder, '1.0.1', '26.3')
            (Path(folder) / 'unexpected.jar').write_bytes(b'unexpected')
            with self.assertRaises(ValueError):
                verify_mod_jar.verify_pair(folder, '1.0.1')
