#!/usr/bin/env python3
"""Exercise access protection, persistent memory, retrieval, and the live model."""
import json
from pathlib import Path
import secrets
import urllib.request
import urllib.error

root = Path(__file__).resolve().parent
config = json.loads((root / 'credentials.json').read_text())
workspace = secrets.token_hex(32)
headers = {'Authorization': 'Bearer ' + config['RECALL_ACCESS_TOKEN'], 'X-Workspace-Key': workspace, 'Content-Type': 'application/json'}
base = 'http://127.0.0.1:18123/api'

def call(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=110) as response:
        return json.load(response)

try:
    try:
        urllib.request.urlopen(base + '/workspace', timeout=10)
        raise RuntimeError('Unauthenticated request unexpectedly allowed')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    print('Unauthenticated API access: rejected')
    nonce = 'RECALL-' + secrets.token_hex(4)
    call('/memories', {'title': '部署验收偏好', 'content': '本次验收代号是 ' + nonce, 'kind': 'preference'})
    call('/documents', {'title': '验收知识库', 'content': '测试环境使用 Turing 用户目录保存长期记忆。', 'kind': 'document'})
    assert call('/workspace')['memories'][0]['content'].endswith(nonce)
    print('Memory save and reload: passed')
    result = call('/chat', {'message': '请告诉我本次验收代号，并依据知识库说明长期记忆保存在何处。', 'provider': 'local', 'useMemory': True})
    assert nonce in result['answer'], 'Live answer failed to recall the test nonce'
    assert len(result['sources']) > 0, 'Local retrieval returned no sources'
    assert len(result['memories']) == 1
    assert len(call('/workspace')['messages']) == 2
    print('Live chat with stored memory and Local retrieval: passed')
    print('Selected model:', config['CHAT_MODEL'])
    print('History persistence: passed')
finally:
    test_file = root / 'data' / (workspace + '.json')
    if test_file.exists():
        test_file.unlink()
