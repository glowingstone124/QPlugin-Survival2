import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('orin_trigger', Path(__file__).with_name('orin-trigger.py'))
client = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(client)


class OrinTriggerTest(unittest.TestCase):
    def test_waits_for_success_and_does_not_print_credentials(self):
        with tempfile.TemporaryDirectory() as root:
            config = Path(root) / 'control.json'
            token = 'private-test-token'
            config.write_text(json.dumps({'port': 9913, 'tokens': {'qplugin-build': token}}))
            out = io.StringIO()
            responses = [{'jobId': 'job', 'status': 'QUEUED'}, {'status': 'RUNNING'}, {'status': 'SUCCESS'}]
            with patch.object(client, 'request', side_effect=responses) as request, patch.object(client.time, 'sleep'), contextlib.redirect_stdout(out):
                self.assertEqual(0, client.main(['--config', str(config), '--pipeline', 'qplugin-build', '--revision', 'a' * 40]))
            self.assertNotIn(token, out.getvalue())
            self.assertIn('SUCCESS', out.getvalue())
            self.assertEqual({'inputs': {'revision': 'a' * 40}}, request.call_args_list[0].args[3])

    def test_failed_job_is_not_reported_as_success(self):
        with patch.object(client, 'request', return_value={'status': 'FAILED'}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, client.watch('http://localhost', 'token', 'job', 1))

    def test_timeout_retains_job_id_without_resubmitting(self):
        output = io.StringIO()
        with patch.object(client, 'request') as request, contextlib.redirect_stdout(output):
            self.assertEqual(4, client.watch('http://localhost', 'token', 'job-123', 0))
            request.assert_not_called()
        self.assertIn('job-123', output.getvalue())
        self.assertIn('UNCONFIRMED', output.getvalue())

    def test_invalid_release_never_contacts_orin(self):
        with patch.object(client, 'request') as request, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                client.main(['--pipeline', 'qplugin-deploy-creative', '--release-commit', '../wrong'])
            request.assert_not_called()


if __name__ == '__main__': unittest.main()
