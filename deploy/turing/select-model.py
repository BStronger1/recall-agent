#!/usr/bin/env python3
"""Select an available small chat model and validate a minimal completion."""
import json
from pathlib import Path
import urllib.request
import urllib.error

root = Path(__file__).resolve().parent
path = root / 'credentials.json'
config = json.loads(path.read_text())
headers = {'Authorization': 'Bearer ' + config['MODEL_API_KEY'], 'Content-Type': 'application/json', 'User-Agent': 'Recall-Agent/0.1'}
request = urllib.request.Request(config['MODEL_BASE_URL'] + '/models', headers=headers)
with urllib.request.urlopen(request, timeout=40) as response:
    available = {row['id'] for row in json.load(response).get('data', [])}
choices = ['gpt-4.1-mini', 'gpt-4o-mini', 'deepseek-chat', 'DMXAPI-qwen3.5-flash']
for model in choices:
    if model not in available:
        continue
    body = json.dumps({'model': model, 'messages': [{'role': 'user', 'content': 'Reply with only OK.'}], 'max_tokens': 16, 'stream': False}).encode()
    try:
        request = urllib.request.Request(config['MODEL_BASE_URL'] + '/chat/completions', data=body, headers=headers)
        with urllib.request.urlopen(request, timeout=60) as response:
            result = json.load(response)
        text = result.get('choices', [{}])[0].get('message', {}).get('content', '')
        if not text:
            print('Empty completion:', model)
            continue
        config['CHAT_MODEL'] = model
        path.write_text(json.dumps(config, indent=2) + '\n')
        path.chmod(0o600)
        print('Selected model:', model)
        print('Live completion verified:', text.strip()[:40])
        break
    except urllib.error.HTTPError as error:
        print('Model test failed:', model, 'HTTP', error.code)
    except Exception as error:
        print('Model test failed:', model, type(error).__name__)
else:
    raise SystemExit('No tested model succeeded; configure CHAT_MODEL explicitly.')
