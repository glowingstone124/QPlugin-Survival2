import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import uuid
import zipfile

spec = importlib.util.spec_from_file_location("deploy_hot", Path(__file__).with_name("deploy-hot.py"))
deploy_hot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy_hot)


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.release = self.root / "v2"
        self.release.mkdir()
        artifact = self.release / "demo.jar"
        artifact.write_bytes(b"fixture-module")
        self.manifest = {"formatVersion": 1, "apiVersion": 1, "releaseId": "v2", "hosts": {"QuantumPlugin": "a" * 64},
                         "modules": [{"id": "demo", "version": "v2", "file": "demo.jar",
                                      "sha256": deploy_hot.sha256(artifact), "size": artifact.stat().st_size}], "plugins": []}
        (self.release / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        self.hot = self.root / "hot"
        self.hot.mkdir()
        (self.hot / "results").mkdir()
        self.instance = str(uuid.uuid4())
        self.status = {"formatVersion": 1, "apiVersion": 1, "hostName": "QuantumPlugin", "hostCodeSha256": "a" * 64,
                       "instanceId": self.instance, "running": True, "phase": "ready", "restartRequired": False,
                       "updatedAt": int(time.time() * 1000), "activeRelease": "v1"}
        self.save_status()

    def tearDown(self):
        self.temporary.cleanup()

    def save_status(self):
        (self.hot / "status.json").write_text(json.dumps(self.status), encoding="utf-8")

    def test_waits_for_matching_host_confirmation(self):
        self.assert_confirmed_deployment()

    def test_api_two_release_uses_the_same_confirmed_deployment_protocol(self):
        self.manifest["apiVersion"] = 2
        (self.release / "manifest.json").write_text(json.dumps(self.manifest))
        self.status["apiVersion"] = 2
        self.save_status()
        self.assert_confirmed_deployment()

    def test_api_upgrade_requires_host_installation_before_any_request(self):
        self.manifest["apiVersion"] = 2
        (self.release / "manifest.json").write_text(json.dumps(self.manifest))
        self.assertEqual("RESTART_REQUIRED", deploy_hot.deploy(self.release, self.hot)["code"])
        self.assertFalse((self.hot / "requests").exists())

    def assert_confirmed_deployment(self):
        errors = []

        def host():
            try:
                deadline = time.monotonic() + 3
                while time.monotonic() < deadline:
                    requests = list((self.hot / "requests").glob("*.json")) if (self.hot / "requests").exists() else []
                    if requests:
                        request = json.loads(requests[0].read_text())
                        self.assertEqual("v1", request["expectedActive"])
                        self.assertEqual(self.instance, request["instanceId"])
                        self.assertTrue((self.hot / "releases/v2/demo.jar").exists())
                        result = {"requestId": request["requestId"], "instanceId": self.instance,
                                  "result": {"ok": True, "code": "ACTIVE", "releaseId": "v2"}}
                        deploy_hot.atomic_json(self.hot / "results" / requests[0].name, result)
                        return
                    time.sleep(0.01)
                raise AssertionError("No deployment request arrived")
            except BaseException as error:
                errors.append(error)

        worker = threading.Thread(target=host)
        worker.start()
        try:
            result = deploy_hot.deploy(self.release, self.hot, timeout=3)
            self.assertEqual("ACTIVE", result["code"])
        finally:
            worker.join(4)
        self.assertFalse(errors, errors)

    def test_changed_host_requires_restart_without_submitting_a_request(self):
        self.status["hostCodeSha256"] = "b" * 64
        self.save_status()
        self.assertEqual("RESTART_REQUIRED", deploy_hot.deploy(self.release, self.hot)["code"])
        self.assertFalse((self.hot / "requests").exists())

    def test_wrong_backend_is_rejected_before_staging_or_submitting(self):
        with self.assertRaisesRegex(ValueError, "Wrong backend"):
            deploy_hot.deploy(self.release, self.hot, expected_host="QuantumPlugin-Creative")
        self.assertFalse((self.hot / "releases").exists())
        self.assertFalse((self.hot / "requests").exists())

    def test_timeout_does_not_claim_success_or_retry_an_unconfirmed_update(self):
        with self.assertRaises(TimeoutError):
            deploy_hot.deploy(self.release, self.hot, timeout=0.01)
        self.assertEqual(1, len(list((self.hot / "requests").glob("*.json"))))

    def test_tampered_artifact_is_rejected(self):
        (self.release / "demo.jar").write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            deploy_hot.deploy(self.release, self.hot)

    def test_immutable_release_cannot_be_overwritten(self):
        deploy_hot.stage(self.release, self.hot, self.manifest)
        changed = json.loads(json.dumps(self.manifest))
        changed["modules"][0]["version"] = "changed"
        with self.assertRaises(ValueError):
            deploy_hot.stage(self.release, self.hot, changed)

    def test_stale_status_is_rejected(self):
        self.status["updatedAt"] -= 60000
        self.save_status()
        with self.assertRaises(ValueError):
            deploy_hot.deploy(self.release, self.hot)

    def test_zip_traversal_and_symlink_entries_are_rejected(self):
        archive = self.root / "bad.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("../escape", "bad")
        with self.assertRaises(ValueError), deploy_hot.unpack(archive):
            pass
        with zipfile.ZipFile(archive, "w") as output:
            entry = zipfile.ZipInfo("link")
            entry.external_attr = 0o120777 << 16
            output.writestr(entry, "target")
        with self.assertRaises(ValueError), deploy_hot.unpack(archive):
            pass

    def test_normal_archive_is_validated_before_staging(self):
        archive = self.root / "release.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for file in self.release.iterdir():
                output.write(file, file.name)
        with deploy_hot.unpack(archive) as directory:
            self.assertEqual(self.manifest, deploy_hot.validate(directory))


if __name__ == "__main__":
    unittest.main()
