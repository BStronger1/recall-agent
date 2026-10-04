#!/usr/bin/env python3
"""Verify personal model settings using server credentials without exposing keys."""
import json
from pathlib import Path
import secrets
import urllib.request
import urllib.error

root = Path(__file__).resolve().parent
config = json.loads((root / 'credentials.json').read_text())
workspace = secrets.token_hex(32)
headers = {'Authorization': 'Bearer ' + config['RECALL_ACCESS_TOKEN'],
           'X-Workspace-Key': workspace, 'Content-Type': 'application/json'}
base = 'http://' + config.get('SERVER_ADDRESS', '127.0.0.1') + ':18123/api'
personal = {'baseUrl': config['MODEL_BASE_URL'], 'model': config['CHAT_MODEL'], 'apiKey': config['MODEL_API_KEY']}

def call(path, method='GET', body=None, workspace_key=None):
    request_headers = dict(headers)
    if workspace_key:
        request_headers['X-Workspace-Key'] = workspace_key
    request = urllib.request.Request(base + path, method=method, headers=request_headers,
                                     data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(request, timeout=110) as response:
        result = json.load(response)
        assert config['MODEL_API_KEY'] not in json.dumps(result), 'Key appeared in response'
        return result

try:
    try:
        urllib.request.urlopen(base + '/model-settings', timeout=10)
        raise RuntimeError('Unauthenticated settings access allowed')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    saved = call('/model-settings', 'PUT', personal)
    assert saved['custom'] and saved['keyConfigured']
    assert 'apiKey' not in saved and 'encryptedKey' not in saved
    disk = (root / 'data' / (workspace + '.model.json')).read_text()
    assert config['MODEL_API_KEY'] not in disk
    assert call('/model-settings')['model'] == config['CHAT_MODEL']
    assert not call('/model-settings', workspace_key=secrets.token_hex(32))['custom']
    print('Authenticated settings, encrypted storage, redacted responses and workspace isolation: passed')
    personal['apiKey'] = ''
    assert call('/model-settings/test', 'POST', personal)['ok']
    print('Live connection test with retained personal key: passed')
    nonce = 'PERSONAL-' + secrets.token_hex(4)
    result = call('/chat', 'POST', {'message': '请原样回复：' + nonce, 'provider': 'none', 'useMemory': False})
    assert nonce in result['answer']
    assert len(call('/workspace')['messages']) == 2
    print('Live conversation using personal model: passed')
    assert not call('/model-settings', 'DELETE')['custom']
    assert not (root / 'data' / (workspace + '.model.json')).exists()
    print('Personal key deletion and default restoration: passed')
finally:
    for suffix in ('.json', '.model.json'):
        path = root / 'data' / (workspace + suffix)
        if path.exists():
            path.unlink()
