# Server Git Deploy

Production runs from:

```text
/home/ubuntu/bhzn-todesk
```

Git checkout used for deploys:

```text
/home/ubuntu/bhzn-todesk-git
```

Deploy script:

```bash
bash scripts/deploy-server-git.sh
```

The script:

1. Clones or updates `https://github.com/lengyan11001/todesk.git`.
2. Backs up the current production `package.json`, `package-lock.json`, `src`, and `public`.
3. Rsyncs the repository `server/` directory into `/home/ubuntu/bhzn-todesk`.
4. Preserves production data:
   - `/home/ubuntu/bhzn-todesk/data`
   - `/home/ubuntu/bhzn-todesk/public/downloads`
   - `/home/ubuntu/bhzn-todesk/node_modules`
   - `/home/ubuntu/bhzn-todesk/deploy-backups`
   - `/home/ubuntu/bhzn-todesk/logs`
5. Validates that `/etc/bhzn-turn/server.env` is readable before stopping the old server.
6. Runs `npm install --omit=dev`.
7. Restarts the Node process with the previous `PORT`, `HOST`, and admin env values.
8. Loads TURN/RTC env from `/etc/bhzn-turn/server.env` when present.
9. Runs a local `/api/health` check.

## Production Env

Current defaults:

```bash
PORT=38080
HOST=0.0.0.0
GIT_DEPTH=1
APP_DIR=/home/ubuntu/bhzn-todesk
CHECKOUT_DIR=/home/ubuntu/bhzn-todesk-git
TURN_ENV_FILE=/etc/bhzn-turn/server.env
```

TURN env file:

```bash
RTC_STUN_URLS=stun:139.199.168.36:3478
RTC_TURN_URLS=turn:139.199.168.36:3478?transport=udp
RTC_TURN_SECRET=<server-only-secret>
RTC_TURN_TTL_SECONDS=3600
```

Do not commit TURN secrets to Git.

## Impact

Deploying restarts the Node process once. Current WebSocket controller/device sessions will disconnect and reconnect. The static downloads and persisted device/user state are not replaced.

Coturn is separate from Node. Restarting Node does not restart coturn.

## Rollback

Backups are stored under:

```text
/home/ubuntu/bhzn-todesk/deploy-backups/<timestamp>-git-deploy
```

Manual rollback:

```bash
BACKUP=/home/ubuntu/bhzn-todesk/deploy-backups/<timestamp>-git-deploy
rsync -a --delete --exclude 'downloads/' "$BACKUP/public/" /home/ubuntu/bhzn-todesk/public/
rsync -a "$BACKUP/src/" /home/ubuntu/bhzn-todesk/src/
cp "$BACKUP/package.json" /home/ubuntu/bhzn-todesk/package.json
cp "$BACKUP/package-lock.json" /home/ubuntu/bhzn-todesk/package-lock.json
cd /home/ubuntu/bhzn-todesk
npm install --omit=dev
```

Then restart Node with the same env values.
