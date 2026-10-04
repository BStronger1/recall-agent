#!/usr/bin/env python3
"""Run the JVM under a user-owned tmux supervisor with bounded logs."""
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import signal
import subprocess
import time

root = Path(__file__).resolve().parent
os.umask(0o077)
logger = logging.getLogger('recall')
logger.setLevel(logging.INFO)
handler = RotatingFileHandler(root / 'logs' / 'service.log', maxBytes=5_000_000, backupCount=3)
handler.setFormatter(logging.Formatter('%(asctime)s %(message)s'))
logger.addHandler(handler)
stop = False
child = None

def shutdown(*_):
    global stop
    stop = True
    if child and child.poll() is None:
        child.terminate()

for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
    signal.signal(sig, shutdown)

while not stop:
    try:
        config = json.loads((root / 'credentials.json').read_text())
        if not config.get('RECALL_ACCESS_TOKEN'):
            raise RuntimeError('Access token required')
        env = dict(os.environ, **config)
        env.update(PORT='18123', SERVER_ADDRESS=config.get('SERVER_ADDRESS', '127.0.0.1'), RECALL_DATA_DIR=str(root / 'data'))
        java = root / 'runtime' / 'jre21' / 'bin' / 'java'
        child = subprocess.Popen([str(java), '-Xms64m', '-Xmx384m', '-jar', str(root / 'releases' / 'recall-agent.jar')], cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in child.stdout:
            logger.info(line.rstrip())
        logger.info('JVM exited with code %s', child.wait())
    except Exception as e:
        logger.error('Startup failed: %s', type(e).__name__)
    if not stop:
        time.sleep(10)
