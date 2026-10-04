#!/usr/bin/env python3
"""Exercise throwaway accounts on the live service. Never print credentials.

Run with --prepare before an app restart and --verify after it. Test credentials
are kept in a private temporary file. Verify removes only its own account data;
restart the app afterwards to clear its in-memory cache of removed test accounts.
"""
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import sys
import urllib.request
import urllib.error

os.umask(0o077)
root = Path(__file__).resolve().parent
config = json.loads((root / 'credentials.json').read_text())
base = 'http://' + config.get('SERVER_ADDRESS', '127.0.0.1') + ':18123/api'
state_path = root / '.accounts-smoke.json'

def browser():
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar)), jar

def call(client, path, method='GET', body=None, extra=None, expect=200):
    headers = {'Content-Type': 'application/json', 'X-Recall-Client': 'web'}
    headers.update(extra or {})
    request = urllib.request.Request(base + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
    try:
        response = client.open(request, timeout=110)
    except urllib.error.HTTPError as error:
        assert error.code == expect, 'Unexpected HTTP status: ' + str(error.code)
        return json.load(error)
    with response:
        assert response.status == expect, 'Unexpected successful response'
        value = json.load(response)
        assert config['MODEL_API_KEY'] not in json.dumps(value), 'Key leaked in response'
        return value

def find_workspace(username):
    for path in (root / 'data' / 'accounts').glob('*.json'):
        if json.loads(path.read_text())['username'] == username:
            return path.stem
    raise RuntimeError('Test account missing')

if sys.argv[1:] == ['--prepare']:
    assert not state_path.exists(), 'Previous smoke state exists; verify it first'
    state = {'username': 'smoke_' + secrets.token_hex(6), 'password': secrets.token_urlsafe(30),
             'nonce': 'ACCOUNT-' + secrets.token_hex(4)}
    state_path.write_text(json.dumps(state))
    client, jar = browser()
    call(client, '/auth/register', 'POST', {k: state[k] for k in ('username', 'password')})
    workspace = find_workspace(state['username'])
    stored = (root / 'data' / 'accounts' / (workspace + '.json')).read_text()
    session_cookie = next(c.value for c in jar if c.name == 'recall_session')
    assert state['password'] not in stored and session_cookie not in stored
    assert not call(client, '/model-settings')['keyConfigured']
    call(client, '/chat', 'POST', {'message': 'hello', 'provider': 'none', 'useMemory': False}, expect=400)
    call(client, '/memories', 'POST', {'title': '验收偏好', 'content': '验收代号为 ' + state['nonce'], 'kind': 'preference'})
    call(client, '/model-settings', 'PUT', {'baseUrl': config['MODEL_BASE_URL'], 'model': config['CHAT_MODEL'], 'apiKey': config['MODEL_API_KEY']})
    call(client, '/workspace', extra={'Cookie': 'recall_session=' + session_cookie})
    state['cookie'] = session_cookie
    state_path.write_text(json.dumps(state))
    print('Self-registration, password/session hashes, private model requirement and memory save: passed')
elif sys.argv[1:] == ['--verify']:
    state = json.loads(state_path.read_text())
    client, jar = browser()
    call(client, '/auth/me', extra={'Cookie': 'recall_session=' + state['cookie']})
    call(client, '/auth/login', 'POST', {k: state[k] for k in ('username', 'password')})
    assert state['nonce'] in call(client, '/workspace')['memories'][0]['content']
    assert call(client, '/model-settings')['custom']
    result = call(client, '/chat', 'POST', {'message': '请告诉我记忆中的验收代号。', 'provider': 'local', 'useMemory': True})
    assert state['nonce'] in result['answer']
    assert len(call(client, '/workspace')['messages']) == 2
    call(client, '/auth/logout', 'POST')
    call(client, '/auth/me', expect=401)
    call(client, '/workspace', expect=401)
    workspace = find_workspace(state['username'])
    for path in (root / 'data' / (workspace + '.json'), root / 'data' / (workspace + '.model.json'), root / 'data' / 'accounts' / (workspace + '.json')):
        if path.exists():
            path.unlink()
    state_path.unlink()
    print('Restart persistence, new-device login, live personal-model memory recall and logout: passed')
    print('Synthetic account files removed; restart to clear its cached account.')
else:
    raise SystemExit('Use --prepare or --verify')
