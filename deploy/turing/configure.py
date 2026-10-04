#!/usr/bin/env python3
"""Configure server-only credentials via hidden input; never print API secrets."""
import getpass
import json
import os
import pathlib
import secrets
import urllib.request
import urllib.error
import sys

root = pathlib.Path(__file__).resolve().parent
file = root / 'credentials.json'
config = json.loads(file.read_text()) if file.exists() else {}
if '--configure' in sys.argv:
    key = getpass.getpass('Model API key (hidden): ').strip()
    if not key:
        raise SystemExit('Empty key; no configuration changed.')
    config.update(MODEL_API_KEY=key, MODEL_BASE_URL=input('Model base URL: ').strip().rstrip('/'))
    config.setdefault('RECALL_ACCESS_TOKEN', secrets.token_urlsafe(32))
    config.setdefault('CHAT_MODEL', '')
    old = os.umask(0o077)
    try:
        temp = root / 'credentials.json.tmp'
        temp.write_text(json.dumps(config, indent=2) + '\n')
        os.chmod(temp, 0o600)
        temp.replace(file)
    finally:
        os.umask(old)
    print('Server credentials saved (permissions 0600).')
if '--models' in sys.argv:
    req = urllib.request.Request(config['MODEL_BASE_URL'] + '/models', headers={'Authorization': 'Bearer ' + config['MODEL_API_KEY'], 'User-Agent': 'Recall-Agent/0.1'})
    try:
        with urllib.request.urlopen(req, timeout=40) as response:
            models = json.load(response).get('data', [])
        ids = sorted(m['id'] for m in models if isinstance(m, dict) and 'id' in m)
        candidates = [m for m in ids if any(s in m.lower() for s in ['mini', 'flash', 'deepseek-chat'])]
        print(json.dumps(candidates[:60] or ids[:30], ensure_ascii=False))
    except urllib.error.HTTPError as e:
        print('Model listing HTTP status:', e.code)
    except Exception as e:
        print('Model listing failed:', type(e).__name__)
if '--model' in sys.argv:
    config['CHAT_MODEL'] = sys.argv[sys.argv.index('--model') + 1]
    file.write_text(json.dumps(config, indent=2) + '\n')
    os.chmod(file, 0o600)
    print('Selected model:', config['CHAT_MODEL'])
if '--access-file' in sys.argv:
    out = root / 'access-token.txt'
    old = os.umask(0o077)
    try:
        out.write_text(config['RECALL_ACCESS_TOKEN'] + '\n')
        os.chmod(out, 0o600)
    finally:
        os.umask(old)
    print('Access token file prepared; contents not printed.')
