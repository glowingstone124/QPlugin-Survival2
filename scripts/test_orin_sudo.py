"""Verify the privileged helper's process selection without installing sudoers or sending signals."""
import contextlib
import io
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch


class OrinSudoTest(unittest.TestCase):
    def setUp(self):
        script = (Path(__file__).resolve().parent.parent / 'ci/orin/setup-orin-sudo.sh').read_text()
        source = script.split("<<'PYTHON'\n", 1)[1].split('\nPYTHON\n', 1)[0]
        self.module = types.ModuleType('qplugin_admin_test')
        exec(compile(source, 'qplugin-orin-admin', 'exec'), self.module.__dict__)
        self.temp = tempfile.TemporaryDirectory()
        self.module.PROC_DIRECTORY = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def process(self, pid, cwd='/opt/server/orin', jar='Orin-all.jar', executable='/usr/bin/java'):
        root = self.module.PROC_DIRECTORY / str(pid)
        root.mkdir()
        (root / 'cwd').symlink_to(cwd)
        (root / 'exe').symlink_to(executable)
        (root / 'cmdline').write_bytes(('java\0-jar\0' + jar + '\0').encode())
        (root / 'stat').write_text(str(pid) + ' (java) S ' + ' '.join(['0'] * 18 + ['123456']))

    def test_selects_only_the_fixed_orin_jar_and_directory(self):
        self.process(101)
        self.process(102, cwd='/opt/server/qo2', jar='paper.jar')
        self.process(103, cwd='/opt/another-orin')
        self.process(104, jar='/opt/another-orin/Orin-all.jar')
        self.process(105, executable='/bin/bash')
        self.assertEqual([101], [p['pid'] for p in self.module.find_processes()])

    def run_restart(self):
        with patch.object(self.module.os, 'geteuid', return_value=0), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return self.module.main(['restart'])

    def test_busy_pipeline_is_not_signalled(self):
        self.process(101)
        children = self.module.PROC_DIRECTORY / '101/task/101'
        children.mkdir(parents=True)
        (children / 'children').write_text('202')
        with patch.object(self.module.os, 'kill') as signal:
            self.assertEqual(3, self.run_restart())
            signal.assert_not_called()

    def test_pid_reuse_is_not_signalled(self):
        process = {'pid': 101, 'children': [], 'startToken': 'before'}
        with patch.object(self.module, 'find_processes', return_value=[process]), patch.object(self.module, 'start_token', return_value='after'), patch.object(self.module.os, 'kill') as signal:
            self.assertEqual(4, self.run_restart())
            signal.assert_not_called()

    def test_idle_orin_receives_only_sigterm(self):
        self.process(101)
        with patch.object(self.module.os, 'kill') as signal:
            self.assertEqual(0, self.run_restart())
            signal.assert_called_once_with(101, self.module.signal.SIGTERM)


if __name__ == '__main__':
    unittest.main()
