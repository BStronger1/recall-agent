#!/usr/bin/env python3
"""Install a single user-level boot entry without touching other cron entries."""
from pathlib import Path
import subprocess
import shlex
root = Path(__file__).resolve().parent
current = subprocess.run(['crontab', '-l'], capture_output=True, text=True)
if current.returncode not in (0, 1):
    raise SystemExit('Cannot read user crontab; no changes made.')
marker = '# recall-agent-user-service'
lines = [line for line in current.stdout.splitlines() if marker not in line]
lines.append('@reboot /bin/bash ' + shlex.quote(str(root / 'start.sh')) + ' ' + marker)
subprocess.run(['crontab', '-'], input='\n'.join(lines) + '\n', text=True, check=True)
print('User boot entry installed; existing unrelated entries preserved.')
