"""Exercise JDK selection through the real build shell using an isolated local Git fixture."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent.parent / 'ci/orin/actions/qplugin-build/build.sh'


@unittest.skipUnless(shutil.which('git') and shutil.which('bash'), 'Git and Bash are required')
class BuildJdkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.jre = self.fake_java('inherited jre', compiler=False)
        self.jdk = self.fake_java('selected jdk', compiler=True)
        self.observation = self.root / 'observation.json'
        self.env = os.environ.copy()
        for key in list(self.env):
            if key.startswith(('QPLUGIN_', 'ORIN_CONTEXT_')): self.env.pop(key)
        self.env.update(JAVA_HOME=str(self.jre), QPLUGIN_REPOSITORY=str(self.root / 'repo'),
            QPLUGIN_RELEASE_STORE=str(self.root / 'store'), ORIN_CONTEXT_RECEIPT=str(self.root / 'receipt'),
            ORIN_CONTEXT_REVISION='main', QPLUGIN_TEST_OBSERVATION=str(self.observation))
        self.env['PATH'] = str(self.jre / 'bin') + os.pathsep + self.env['PATH']

    def tearDown(self):
        self.temp.cleanup()

    def fake_java(self, name, compiler):
        home = self.root / name
        (home / 'bin').mkdir(parents=True)
        for program in ['java'] + (['javac'] if compiler else []):
            target = home / 'bin' / program
            target.write_text('#!/usr/bin/env bash\nprintf "fake JDK 25\\n"\n')
            target.chmod(0o755)
        return home

    def repository(self):
        repo = self.root / 'repo'; repo.mkdir()
        (repo / 'scripts').mkdir(); (repo / 'scripts/__init__.py').touch()
        (repo / 'scripts/test_fixture.py').write_text('''import unittest
from pathlib import Path
class ReleaseFixtureTest(unittest.TestCase):
    def test_release_was_created_before_tests(self):
        self.assertEqual(1, len(list(Path('build/releases').glob('*.zip'))))
''')
        wrapper = repo / 'gradlew'
        wrapper.write_text('''#!/usr/bin/env bash
set -euo pipefail
python3 - "$@" <<'PY'
import json, os, pathlib, shutil, sys
args = sys.argv[1:]
commit = next(value.split('=', 1)[1] for value in args if value.startswith('-PreleaseId='))
out = pathlib.Path('build/releases'); out.mkdir(parents=True)
(out / (commit + '.zip')).write_bytes(b'isolated-release')
pathlib.Path(os.environ['QPLUGIN_TEST_OBSERVATION']).write_text(json.dumps({
    'javaHome': os.environ['JAVA_HOME'], 'java': shutil.which('java'),
    'javac': shutil.which('javac'), 'args': args}))
PY
''')
        wrapper.chmod(0o755)
        for args in [['init', '--quiet'], ['symbolic-ref', 'HEAD', 'refs/heads/main'],
                     ['config', 'user.name', 'QPlugin test'], ['config', 'user.email', 'test@example.invalid'],
                     ['config', 'commit.gpgsign', 'false'], ['config', 'core.hooksPath', str(self.root / 'no-hooks')],
                     ['add', '.'], ['commit', '--quiet', '-m', 'fixture']]:
            subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True)

    def run_build(self):
        return subprocess.run(['bash', str(SCRIPT)], env=self.env, text=True, capture_output=True, timeout=20)

    def test_project_jdk_overrides_inherited_jre_and_pins_gradle_jvm(self):
        self.repository()
        self.env['QPLUGIN_JAVA_HOME'] = str(self.jdk)
        result = self.run_build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        observed = json.loads(self.observation.read_text())
        self.assertEqual(str(self.jdk), observed['javaHome'])
        self.assertEqual(str(self.jdk / 'bin/java'), observed['java'])
        self.assertEqual(str(self.jdk / 'bin/javac'), observed['javac'])
        self.assertIn('-Dorg.gradle.java.home=' + str(self.jdk), observed['args'])
        self.assertTrue((self.root / 'receipt').is_file())

    def test_orin_context_jdk_has_priority_over_environment(self):
        self.repository()
        self.env['QPLUGIN_JAVA_HOME'] = str(self.jre)
        self.env['ORIN_CONTEXT_JAVA_HOME'] = str(self.jdk)
        result = self.run_build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(str(self.jdk), json.loads(self.observation.read_text())['javaHome'])

    def test_inherited_full_jdk_remains_supported(self):
        self.repository()
        self.env['JAVA_HOME'] = str(self.jdk)
        result = self.run_build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_jre_is_rejected_before_checkout_or_build(self):
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('full JDK', result.stderr)
        self.assertIn('QPLUGIN_JAVA_HOME', result.stderr)
        self.assertFalse(self.observation.exists())
        self.assertFalse((self.root / 'receipt').exists())

    def test_missing_java_home_is_rejected_without_guessing_a_system_jre(self):
        self.env.pop('JAVA_HOME')
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('QPLUGIN_JAVA_HOME', result.stderr)
        self.assertFalse(self.observation.exists())


if __name__ == '__main__': unittest.main()
