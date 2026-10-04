# User-owned Linux deployment

This alternative runs without Docker or root privileges. It uses Java 21, Python 3,
tmux, and a user crontab entry. Credentials and persistent data stay in the remote
user's private application directory. Do not commit credentials or access tokens.

1. Build the frontend with `npm ci && npm run build` in `yu-ai-agent-frontend`.
2. Build the combined JAR with `mvn -f recall-server/pom.xml package`.
3. On the destination, create `~/.local/share/recall-agent` with mode `0700` and
   subdirectories `releases`, `runtime`, `data`, and `logs`.
4. Install a trusted Java 21 distribution at `runtime/jre21` and copy the JAR to
   `releases/recall-agent.jar`. Copy these Python and shell scripts into the
   application directory, keeping credentials outside the Git checkout.
5. Run `python3 configure.py --configure` in an interactive SSH session. The model
   key prompt does not echo. The base URL is the Chat Completions API prefix.
6. Set the model using `python3 configure.py --model MODEL_NAME`, or run
   `python3 select-model.py` to discover and test one supported small model.
   The latter makes short, potentially billable API requests.
7. Run `bash start.sh`. The JVM binds only to `127.0.0.1:18123`, uses at most
   384 MB heap, and is restarted by the supervisor if it exits unexpectedly.
8. Optionally run `python3 install-autostart.py` to add a user `@reboot` entry.
   This preserves unrelated cron entries; the host must run cron at boot.
9. Run `python3 smoke-test.py` for a live memory/retrieval/model test. It uses and
   removes its own synthetic workspace. This makes one model API request.

Access using an SSH tunnel from your computer:

```bash
ssh -N -L 127.0.0.1:18123:127.0.0.1:18123 USER@SERVER
```

Open `http://127.0.0.1:18123`. Run `python3 configure.py --access-file` on the
server to create a private `access-token.txt`, then transfer it securely and use
the token on the site's settings page. This token is separate from the model key.

Logs rotate at 5 MB with three backups. Data lives in `data/`. Stop with
`tmux kill-session -t recall-agent` and restart with `bash start.sh`. Stop the
service before replacing the JAR. A boot entry is not verification of actual
post-reboot behavior; no server restart is required for installation.

This setup provides private access over SSH, not a public website. A public
deployment needs an approved HTTPS reverse proxy/domain and reachable ingress.

For direct campus/VPN access, set `SERVER_ADDRESS` in the private
`credentials.json` to the server's specific campus IPv4 address and restart the
service. Use `http://CAMPUS_IP:18123`; routing and firewall rules must permit it.
Bind to that interface rather than all interfaces. The access token remains
required. HTTP does not itself encrypt traffic, so use this endpoint only on a
trusted campus/VPN network; prefer the SSH tunnel for encrypted transport.
For SSH forwarding after this change, replace the remote `127.0.0.1` in `-L`
with the campus address. The model API key stays on the server.

Users can connect with the site token and then set their own compatible model
in Settings. Personal settings override the site default in that workspace.
Model keys are encrypted in `data/<workspace>.model.json` using the persistent
`data/.model-encryption-key`. Back up the whole private data directory, including
this hidden file; losing it makes saved personal keys unreadable. Keep directory
permissions private. Users should submit keys over HTTPS or the SSH tunnel.
The connection test makes a short billable request without saving settings.
`python3 smoke-model-settings.py` verifies personal settings with the existing
site credentials in a synthetic workspace, then removes its test data. It makes
two model requests and never prints keys.
