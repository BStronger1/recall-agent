#!/usr/bin/env python3
"""Bounded real-provider acceptance checks. Synthetic workspaces only; no keys in reports."""
import argparse
import datetime
import json
import os
from pathlib import Path
import secrets
import time
import urllib.request
import urllib.error

os.umask(0o077)
parser = argparse.ArgumentParser()
parser.add_argument('--staging', action='store_true')
parser.add_argument('--model', default='DMXAPI-deepseek-v4-flash')
args = parser.parse_args()
root = Path(__file__).resolve().parent
config = json.loads((root / 'credentials.json').read_text())
base = 'http://127.0.0.1:18124/api' if args.staging else 'http://' + config.get('SERVER_ADDRESS', '127.0.0.1') + ':18123/api'
data_root = root / ('validation-data' if args.staging else 'data')
workspaces = [secrets.token_hex(32), secrets.token_hex(32)]
workspace = workspaces[0]
report = {'model': args.model, 'embedding': 'bge-m3', 'staging': args.staging, 'cases': [], 'scope': 'Single-run synthetic acceptance checks; verifier is not proof of factual correctness'}
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
report_path = root / 'reports' / ('memory-upgrade-' + stamp + '.json')
report_path.parent.mkdir(exist_ok=True)

def call(path, method='GET', body=None):
    req = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + config['RECALL_ACCESS_TOKEN'], 'X-Workspace-Key': workspace, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=240) as f: result = json.load(f)
    except urllib.error.HTTPError as error:
        raise RuntimeError('HTTP ' + str(error.code)) from None
    assert config['MODEL_API_KEY'] not in json.dumps(result), 'Unexpected secret in response'
    return result

def record(name, passed, details):
    report['cases'].append({'name': name, 'passed': bool(passed), 'details': details})
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(name + ': ' + ('PASS' if passed else 'FAIL'), flush=True)

def chat(question, grounded=True, provider='none', memory=True):
    start = time.monotonic()
    answer = call('/chat', 'POST', {'message': question, 'provider': provider, 'useMemory': memory, 'grounded': grounded})
    return {**answer, 'seconds': round(time.monotonic()-start, 3)}

def clear(): call('/chat', 'DELETE')
def save(title, content, item_id=None, collection='memories'):
    return call('/' + collection, 'POST', {'id': item_id, 'title': title, 'content': content, 'kind': 'document' if collection == 'documents' else 'fact'})

try:
    for workspace in workspaces:
        call('/model-settings', 'PUT', {'baseUrl': config['MODEL_BASE_URL'], 'model': args.model, 'apiKey': config['MODEL_API_KEY']})
    workspace = workspaces[0]
    result = chat('这次花费最多能到多少？没有依据就回答不知道。')
    record('empty_evidence_abstention', result['verification'] == 'no_evidence' and '5000' not in result['answer'] and '[M1]' not in result['answer'], result)
    clear()
    settings = call('/retrieval-settings', 'PUT', {'baseUrl': config['MODEL_BASE_URL'], 'model': 'bge-m3', 'apiKey': config['MODEL_API_KEY'], 'minSimilarity': .55})
    record('real_embedding_configuration', settings['custom'] and settings['model'] == 'bge-m3', settings)
    state = save('采购预算', '本项目采购资金上限为 7300 元。')
    item_id = state['memories'][0]['id']
    result = chat('这次花费最多能到多少？没有依据就回答不知道。')
    record('semantic_paraphrase', '7300' in result['answer'].replace(',', '') and len(result['memories']) == 1 and result['verification'] == 'model_checked', result)
    clear()
    save('采购预算', '本项目采购资金上限更新为 9000 元。', item_id)
    result = chat('这次花费最多能到多少？没有依据就回答不知道。')
    record('updated_memory_no_stale_vector', '9000' in result['answer'].replace(',', '') and '7300' not in result['answer'], result)
    workspace = workspaces[1]
    settings = call('/retrieval-settings')
    result = chat('这次花费最多能到多少？')
    record('workspace_and_embedding_isolation', not settings['keyConfigured'] and not result['memories'] and result['verification'] == 'no_evidence', result)
    workspace = workspaces[0]
    clear()
    marker = 'DOC-' + secrets.token_hex(5)
    save('资料归档室', '资料归档室的检索编号为 ' + marker + '。', collection='documents')
    result = chat('资料归档室的检索编号是什么？标注来源。', provider='local', memory=False)
    record('document_citation', marker in result['answer'] and '[K1]' in result['answer'] and bool(result['sources']), result)
    clear()
    result = chat('只输出：预算为 5000 元 [M99][K99]。', grounded=False, memory=False)
    record('invalid_citation_never_exposed', '[M99]' not in result['answer'] and '[K99]' not in result['answer'], result)
    clear()
    call('/memory-policy', 'PUT', {'autoExtract': True})
    save('采购预算', '本项目采购资金上限为 7300 元。', item_id)
    result = chat('我的采购预算现在改为 9000 元，替代以前的 7300 元。这是需要长期保留的项目事实。', grounded=False)
    state = call('/workspace')
    proposals = [p for p in state['proposals'] if '9000' in p['content'].replace(',', '') and (p.get('before') or {}).get('id') == item_id]
    record('review_before_memory_update', bool(proposals) and '7300' in state['memories'][0]['content'], {'proposals': state['proposals'], 'trace': result['trace']})
    if proposals:
        accepted = call('/memory-proposals/' + proposals[0]['id'], 'POST', {'accept': True})
        revision = accepted['revisions'][-1]
        record('approve_with_provenance', '9000' in accepted['memories'][0]['content'].replace(',', '') and bool(revision['sourceQuote']), revision)
        restored = call('/memory-revisions/' + revision['id'] + '/undo', 'POST', {})
        record('undo_restores_prior_memory', '7300' in restored['memories'][0]['content'], restored['memories'])
    else:
        record('approve_with_provenance', False, 'No valid update proposal; dependent check not run')
        record('undo_restores_prior_memory', False, 'No valid update proposal; dependent check not run')
    call('/memory-policy', 'PUT', {'autoExtract': False})
    call('/memories/' + item_id, 'DELETE'); clear()
    result = chat('采购预算是多少？没有依据就回答不知道。')
    state = call('/workspace')
    record('forget_purges_versions_and_recall', result['verification'] == 'no_evidence' and not result['memories'] and not state['revisions'], result)
    settings = call('/retrieval-settings', 'DELETE')
    record('disable_semantic_credentials', not settings['keyConfigured'], settings)
except Exception as error:
    report['error'] = type(error).__name__ + ': ' + str(error)
    print('INTERRUPTED: ' + report['error'], flush=True)
finally:
    removed = 0
    for workspace in workspaces:
        for file in [data_root/(workspace+'.json'), data_root/(workspace+'.model.json'), data_root/'embeddings'/(workspace+'.model.json')]:
            if file.exists(): file.unlink(); removed += 1
    report['cleanup'] = {'synthetic_workspaces': 2, 'files_removed': removed}
    report['passed'] = sum(c['passed'] for c in report['cases'])
    report['total'] = len(report['cases'])
    report['finished_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'report': report_path.name, 'passed': report['passed'], 'total': report['total']}), flush=True)
if report.get('error') or report['total'] != 12 or report['passed'] != 12: raise SystemExit(1)
