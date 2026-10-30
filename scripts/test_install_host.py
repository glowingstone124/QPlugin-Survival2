import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SOURCE = Path(__file__).resolve().parent.parent / 'ci/orin/actions/qplugin-deploy/install-host.py'
SPEC = importlib.util.spec_from_file_location('install_host', SOURCE)
installer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(installer)


class HostInstallationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / 'plugins').mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def test_wrong_backend_path_never_calls_docker(self):
        with patch.object(installer, 'docker') as docker:
            with self.assertRaisesRegex(ValueError, 'does not match'):
                installer.install(self.root / 'release.zip', self.root / 'hot', 'QuantumPlugin-Creative')
            docker.assert_not_called()

    def test_container_selection_requires_exactly_one_matching_mount(self):
        listing = [{'Id': 'a', 'Mounts': [{'Source': str(self.root)}]}, {'Id': 'b', 'Mounts': [{'Source': '/elsewhere'}]}]
        with patch.object(installer, 'docker', side_effect=['a b', json.dumps(listing)]):
            self.assertEqual('a', installer.find_container(self.root)['Id'])
        listing[1]['Mounts'][0]['Source'] = str(self.root)
        with patch.object(installer, 'docker', side_effect=['a b', json.dumps(listing)]):
            with self.assertRaisesRegex(RuntimeError, 'exactly one'):
                installer.find_container(self.root)

    def test_update_folder_cannot_escape_plugins(self):
        (self.root / 'bukkit.yml').write_text('settings:\n  update-folder: ../../elsewhere\n')
        with self.assertRaisesRegex(RuntimeError, 'inside'):
            installer.update_directory(self.root)
        (self.root / 'bukkit.yml').write_text('settings:\n  update-folder: update\n')
        self.assertEqual((self.root / 'plugins/update').resolve(), installer.update_directory(self.root))

    def test_plugin_selection_reads_metadata_instead_of_only_filenames(self):
        expected = self.root / 'plugins/renamed.jar'
        with zipfile.ZipFile(expected, 'w') as z: z.writestr('plugin.yml', 'name: QuantumPlugin\nversion: 1\n')
        with zipfile.ZipFile(self.root / 'plugins/QuantumPlugin-unrelated.jar', 'w') as z: z.writestr('plugin.yml', 'name: OtherPlugin\n')
        self.assertEqual([expected], installer.plugin_jars(self.root / 'plugins'))

    def test_ambiguous_java_processes_are_rejected_before_signalling(self):
        with patch.object(installer, 'docker', return_value='PID COMMAND\n111 java\n222 java\n'):
            with self.assertRaisesRegex(RuntimeError, 'exactly one Java'):
                installer.java_process('container')


if __name__ == '__main__': unittest.main()
