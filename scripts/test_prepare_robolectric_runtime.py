"""Synthetic byte integrity tests only; never download or compile an SDK locally."""
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import prepare_robolectric_runtime as runtime

class RuntimeIntegrityTest(unittest.TestCase):
    def test_valid_cache_needs_no_network(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'cached.jar';path.write_bytes(b'approved bytes')
            with patch.object(runtime.urllib.request,'urlopen') as network:
                proof=runtime.materialize(path,'cached.jar',hashlib.sha512(path.read_bytes()).hexdigest())
            network.assert_not_called();self.assertEqual('verified-existing-cache',proof['origin'])
    def test_corrupt_cache_is_rejected_without_silently_replacing_it(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'cached.jar';path.write_bytes(b'corrupt')
            with patch.object(runtime.urllib.request,'urlopen') as network:
                with self.assertRaises(runtime.IntegrityError):runtime.materialize(path,'cached.jar',hashlib.sha512(b'approved').hexdigest())
            network.assert_not_called();self.assertEqual(b'corrupt',path.read_bytes())
    def test_wrong_download_is_not_published_or_accepted_from_a_fallback(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'new.jar'
            with patch.object(runtime.urllib.request,'urlopen',return_value=io.BytesIO(b'wrong')) as network:
                with self.assertRaises(runtime.IntegrityError):runtime.materialize(path,'new.jar',hashlib.sha512(b'approved').hexdigest())
            self.assertEqual(1,network.call_count);self.assertFalse(path.exists());self.assertEqual([],list(path.parent.iterdir()))

if __name__=='__main__':unittest.main()
