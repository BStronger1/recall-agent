#!/usr/bin/env bash
set -euo pipefail
umask 077
root="$(cd "$(dirname "$0")" && pwd)"
export PATH=/usr/bin:/bin
exec 9>"$root/supervisor.lock"
flock -n 9 || exit 0
if ! tmux has-session -t recall-agent 2>/dev/null; then
    tmux new-session -d -s recall-agent "python3 '$root/supervise.py'"
fi
