"""Bind an owner-authorized OAuth credential to one trusted Actions run with RSA-OAEP."""
import base64
import hashlib
import json
from pathlib import Path
import re
import secrets
import subprocess

REPO = 'grigorywork/NoteLS'


def openssl(arguments, data=None):
    result = subprocess.run(['openssl', *arguments], input=data, capture_output=True)
    if result.returncode:
        raise ValueError('Signing setup channel cryptography failed')
    return result.stdout


def validate_request(request):
    if (request.get('repository') != REPO or
            not re.fullmatch(r'\d{1,24}', request.get('run_id', '')) or
            not re.fullmatch(r'\d{1,6}', request.get('attempt', '')) or
            not re.fullmatch(r'[0-9a-f]{64}', request.get('nonce', '')) or
            not re.fullmatch(r'[0-9a-f]{40}', request.get('source_sha', ''))):
        raise ValueError('Invalid owner setup receiver identity')


def prepare(root, run_id, attempt, source_sha):
    root.mkdir(parents=True, mode=0o700)
    public = root / 'public'; public.mkdir()
    private = root / 'receiver-private.pem'; private.touch(mode=0o600)
    openssl(['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:4096', '-out', str(private)])
    pem = openssl(['pkey', '-in', str(private), '-pubout'])
    (public / 'public.pem').write_bytes(pem)
    request = {'repository': REPO, 'run_id': str(run_id), 'attempt': str(attempt),
               'source_sha': source_sha, 'nonce': secrets.token_hex(32),
               'public_key_sha256': hashlib.sha256(pem).hexdigest()}
    validate_request(request)
    (public / 'request.json').write_text(json.dumps(request))
    return request


def seal(token, request, public_key):
    validate_request(request)
    if hashlib.sha256(public_key.read_bytes()).hexdigest() != request['public_key_sha256']:
        raise ValueError('Owner setup public key fingerprint mismatch')
    if not re.fullmatch(r'gho_[A-Za-z0-9_]{20,120}', token):
        raise ValueError('Expected the owner-authorized GitHub CLI OAuth credential')
    payload = {k: request[k] for k in ['repository', 'run_id', 'attempt', 'source_sha', 'nonce']}
    payload['token'] = token
    plain = json.dumps(payload, separators=(',', ':')).encode()
    if len(plain) > 446:
        raise ValueError('Owner setup authorization is too large')
    cipher = openssl(['pkeyutl', '-encrypt', '-pubin', '-inkey', str(public_key),
                      '-pkeyopt', 'rsa_padding_mode:oaep', '-pkeyopt', 'rsa_oaep_md:sha256'], plain)
    return base64.b64encode(cipher).decode()


def unseal(ciphertext, request, private):
    validate_request(request)
    if not isinstance(ciphertext, str) or len(ciphertext) > 1024:
        raise ValueError('Invalid encrypted owner authorization')
    try:
        raw = base64.b64decode(ciphertext, validate=True)
        if len(raw) != 512:
            raise ValueError()
        plain = openssl(['pkeyutl', '-decrypt', '-inkey', str(private),
                         '-pkeyopt', 'rsa_padding_mode:oaep', '-pkeyopt', 'rsa_oaep_md:sha256'], raw)
        payload = json.loads(plain)
        expected = {k: request[k] for k in ['repository', 'run_id', 'attempt', 'source_sha', 'nonce']}
        if (set(payload) != set(expected) | {'token'} or
                any(payload[k] != v for k, v in expected.items()) or
                not isinstance(payload['token'], str) or
                not re.fullmatch(r'gho_[A-Za-z0-9_]{20,120}', payload['token'])):
            raise ValueError()
        return payload['token']
    except (ValueError, KeyError, TypeError, UnicodeError):
        raise ValueError('Encrypted owner authorization does not match this setup run') from None
