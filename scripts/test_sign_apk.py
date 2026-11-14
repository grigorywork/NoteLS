#!/usr/bin/env python3
"""Real Android-signature checks using disposable test keys, never production credentials."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from sign_apk import certificate_digest, sign
from signing_material import SECRET, fingerprint


class SigningContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='notels-test-keys-')
        cls.root = Path(cls.temp.name)
        cls.password = 'disposable-test-password-only'
        cls.environments = []
        for index in range(2):
            key = cls.root / f'test-{index}.p12'
            env = {**os.environ, 'NOTELS_TEST_PASSWORD': cls.password}
            command = [ARGS.keytool, '-genkeypair', '-alias', 'notels-2026', '-keyalg', 'RSA',
                       '-keysize', '2048', '-validity', '2', '-dname', 'CN=Disposable Signing Test',
                       '-storetype', 'PKCS12', '-keystore', str(key), '-storepass:env', 'NOTELS_TEST_PASSWORD']
            subprocess.run(command, env=env, capture_output=True, check=True)
            cert = subprocess.check_output([ARGS.keytool, '-exportcert', '-alias', 'notels-2026',
                       '-keystore', str(key), '-storepass:env', 'NOTELS_TEST_PASSWORD'], env=env, stderr=subprocess.DEVNULL)
            cls.environments.append({SECRET: json.dumps({'keystore_base64': base64.b64encode(key.read_bytes()).decode(),
                                                        'password': cls.password})})
            if index == 0:
                cls.fingerprint = hashlib.sha256(cert).hexdigest()
                cls.pin = cls.root / 'certificate.sha256'
                cls.pin.write_text(cls.fingerprint + '\n')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.output = self.root / self._testMethodName

    def invoke(self, env=None):
        with patch.dict(os.environ, env if env is not None else self.environments[0]):
            return sign(ARGS.apk, ARGS.metadata, self.pin, self.output, ARGS.tools)

    def test_existing_key_signs_and_verifies_without_private_artifacts(self):
        result = self.invoke()
        self.assertEqual(self.fingerprint, result['certificate_sha256'])
        self.assertEqual(3, len(list(self.output.iterdir())))
        apk = next(self.output.glob('*.apk'))
        self.assertIn(hashlib.sha256(apk.read_bytes()).hexdigest(), (self.output / 'SHA256SUMS').read_text())
        cert = subprocess.check_output([str(ARGS.tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
        self.assertEqual(self.fingerprint, certificate_digest(cert))

    def test_second_build_retains_same_certificate(self):
        first = self.invoke()
        self.output = self.root / 'repeat'
        second = self.invoke()
        self.assertEqual(first['certificate_sha256'], second['certificate_sha256'])

    def test_different_private_key_never_reaches_upload_directory(self):
        with self.assertRaisesRegex(ValueError, 'does not match'):
            self.invoke(self.environments[1])
        self.assertFalse(self.output.exists())

    def test_wrong_password_never_reaches_upload_directory(self):
        material = json.loads(self.environments[0][SECRET]); material['password'] = 'wrong-test-password'
        with self.assertRaisesRegex(ValueError, 'signing failed'):
            self.invoke({SECRET: json.dumps(material)})
        self.assertFalse(self.output.exists())

    def test_absent_secret_does_not_generate_a_key(self):
        with self.assertRaisesRegex(ValueError, 'no fallback'):
            self.invoke({SECRET: ''})
        self.assertFalse(self.output.exists())

    def test_invalid_encoding_is_rejected(self):
        material = json.loads(self.environments[0][SECRET]); material['keystore_base64'] = '$invalid!'
        with self.assertRaisesRegex(ValueError, 'encoding'):
            self.invoke({SECRET: json.dumps(material)})
        self.assertFalse(self.output.exists())

    def test_invalid_pin_does_not_publish(self):
        old = self.pin
        self.pin = self.root / 'invalid-pin'
        self.pin.write_text('not-a-certificate')
        try:
            with self.assertRaisesRegex(ValueError, 'pinned signing certificate'):
                self.invoke()
        finally:
            self.pin = old
        self.assertFalse(self.output.exists())

    def test_multiple_certificates_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            certificate_digest(f'Signer #1 certificate SHA-256 digest: {self.fingerprint}\n'
                               f'Signer #2 certificate SHA-256 digest: {self.fingerprint}\n')

    def test_ci_cannot_bootstrap_a_permanent_key(self):
        target = self.root / 'forbidden-ci-private'
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('bootstrap_signing.py')),
                                 '--initialize-new-key', '--private-dir', str(target)],
                                env={**os.environ, 'GITHUB_ACTIONS': 'true'}, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Key generation in CI is forbidden', result.stderr)
        self.assertFalse(target.exists())

    def test_invalid_material_is_rejected_without_echoing_its_content(self):
        with self.assertRaisesRegex(ValueError, 'Invalid signing material') as caught:
            self.invoke({SECRET: '{"password":"disposable-private-marker"'})
        self.assertNotIn('disposable-private-marker', str(caught.exception))
        self.assertFalse(self.output.exists())

    def test_existing_atomic_secret_recovers_the_same_public_fingerprint(self):
        self.assertEqual(self.fingerprint, fingerprint(self.environments[0][SECRET], ARGS.keytool))

    def test_owner_flag_does_not_authorize_an_ordinary_workflow(self):
        target = self.root / 'forbidden-owner-flag'
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('bootstrap_signing.py')),
                                 '--initialize-new-key', '--owner-workflow', '--private-dir', str(target)],
                                env={**os.environ, 'GITHUB_ACTIONS': 'true', 'GITHUB_WORKFLOW': 'Android checks',
                                     'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main'},
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Key generation in CI is forbidden', result.stderr)
        self.assertFalse(target.exists())

    def test_owner_setup_cannot_run_in_a_different_repository(self):
        target = self.root / 'forbidden-owner-repository'
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('bootstrap_signing.py')),
                                 '--initialize-new-key', '--owner-workflow', '--private-dir', str(target)],
                                env={**os.environ, 'GITHUB_ACTIONS': 'true',
                                     'GITHUB_WORKFLOW': 'Configure permanent signing',
                                     'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
                                     'GITHUB_REPOSITORY': 'other/repository'}, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Key generation in CI is forbidden', result.stderr)
        self.assertFalse(target.exists())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--metadata', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--keytool', required=True)
    ARGS = parser.parse_args()
    unittest.main(argv=['test_sign_apk.py'], verbosity=2)
