"""Real RSA-OAEP contract checks with disposable owner credential markers."""
import json
from pathlib import Path
import tempfile
import unittest
import stat
from signing_setup_channel import prepare, seal, unseal


class SetupChannelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name) / 'receiver'
        cls.request = prepare(cls.root, '123456', '1', 'a' * 40)
        cls.token = 'gho_' + 'DisposableCredentialMarker1234567890'

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def encrypted(self):
        return seal(self.token, self.request, self.root / 'public/public.pem')

    def test_only_opaque_ciphertext_is_transported(self):
        cipher = self.encrypted()
        self.assertNotIn(self.token, cipher)
        self.assertEqual(self.token, unseal(cipher, self.request, self.root / 'receiver-private.pem'))

    def test_each_encryption_is_randomized(self):
        self.assertNotEqual(self.encrypted(), self.encrypted())

    def test_another_run_cannot_reuse_the_authorization(self):
        with self.assertRaisesRegex(ValueError, 'does not match'):
            unseal(self.encrypted(), {**self.request, 'run_id': '123457'}, self.root / 'receiver-private.pem')

    def test_another_nonce_cannot_reuse_the_authorization(self):
        with self.assertRaisesRegex(ValueError, 'does not match'):
            unseal(self.encrypted(), {**self.request, 'nonce': 'b' * 64}, self.root / 'receiver-private.pem')

    def test_wrong_public_key_fingerprint_is_rejected_before_delivery(self):
        with self.assertRaisesRegex(ValueError, 'fingerprint mismatch'):
            seal(self.token, {**self.request, 'public_key_sha256': '0' * 64}, self.root / 'public/public.pem')

    def test_corrupted_ciphertext_is_rejected_without_credential_content(self):
        cipher = self.encrypted()
        with self.assertRaisesRegex(ValueError, 'does not match') as caught:
            unseal(('A' if cipher[0] != 'A' else 'B') + cipher[1:], self.request, self.root / 'receiver-private.pem')
        self.assertNotIn(self.token, str(caught.exception))

    def test_a_second_receiver_cannot_replace_private_material(self):
        with self.assertRaises(FileExistsError):
            prepare(self.root, '123456', '2', 'a' * 40)

    def test_another_attempt_cannot_reuse_the_authorization(self):
        with self.assertRaisesRegex(ValueError, 'does not match'):
            unseal(self.encrypted(), {**self.request, 'attempt': '2'}, self.root / 'receiver-private.pem')

    def test_another_source_cannot_reuse_the_authorization(self):
        with self.assertRaisesRegex(ValueError, 'does not match'):
            unseal(self.encrypted(), {**self.request, 'source_sha': 'b' * 40}, self.root / 'receiver-private.pem')

    def test_no_other_repository_receives_authorization(self):
        with self.assertRaisesRegex(ValueError, 'receiver identity'):
            seal(self.token, {**self.request, 'repository': 'other/repository'}, self.root / 'public/public.pem')

    def test_an_installation_token_is_not_an_owner_login(self):
        with self.assertRaisesRegex(ValueError, 'owner-authorized'):
            seal('ghs_' + 'DisposableCredentialMarker1234567890', self.request, self.root / 'public/public.pem')

    def test_the_published_directory_contains_only_public_material(self):
        self.assertEqual({'request.json', 'public.pem'}, {p.name for p in (self.root / 'public').iterdir()})
        self.assertEqual(0o600, stat.S_IMODE((self.root / 'receiver-private.pem').stat().st_mode))


if __name__ == '__main__':
    unittest.main(verbosity=2)
