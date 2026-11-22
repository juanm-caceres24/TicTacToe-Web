# JSON Lines Audit Logging (`com.tictactoe.audit`)

This document describes the file-only **JSON Lines (NDJSON)** audit logging subsystem added in `v0.6.0`.

---

## 1. Design & Format

- **File-only output:** Audit events are written exclusively to the configured file (`logs/audit.log` by default) to avoid flooding server console output under load. Runtime Jetty/Javalin lifecycle logs remain on console, keeping the audit file dedicated solely to structured server/connection/match events.
- **JSON Lines (NDJSON):** Every record is a single valid JSON object terminated by `\n`, serialized via Jackson (guaranteeing RFC 8259 escaping and immunity to newline log injection).
- **Timestamps:** Every event includes `"ts"` in ISO-8601 format with local offset and millisecond precision (e.g., `2026-11-22T18:31:19.884-03:00`).
- **Append-first persistence:** Missing parent directories and files are created automatically. Restarts always append to the active log file (`StandardOpenOption.CREATE, APPEND`) and flush immediately on every write.

---

## 2. Configuration (`configs/server.json`)

```json
"logging": {
  "enabled": true,
  "filePath": "logs/audit.log",
  "logHttpRequests": true,
  "trustProxyHeaders": false,
  "maxFileSizeMb": 10,
  "maxBackupFiles": 5
}
```

| Field | Default | Description |
| :--- | :--- | :--- |
| `enabled` | `true` | Enables or disables file audit logging (`false` installs a zero-I/O no-op logger). |
| `filePath` | `"logs/audit.log"` | Active log file path (overridable at launch with `--log-file <path>`, which preserves `logging.enabled` and does not enable logging if disabled in `server.json`). |
| `logHttpRequests` | `true` | Records `HTTP_ACCESS` events for incoming HTTP requests (IP, method, path, status, latency). |
| `trustProxyHeaders` | `false` | When `false` (recommended for direct port forwarding), always logs the real TCP socket IP and ignores `X-Forwarded-For`. Set `true` only behind a trusted reverse proxy; non-IP values in `X-Forwarded-For` are rejected and fall back to the socket IP. |
| `maxFileSizeMb` | `10` | Maximum active file size (`1..1024` MB) before triggering rotation. |
| `maxBackupFiles` | `5` | Number of rotated backups retained (`0..100`). Total disk cap is `(maxBackupFiles + 1) × maxFileSizeMb`. |

---

## 3. Rolling Size-Based Log Rotation

The active file is **always** `filePath` (e.g., `logs/audit.log`). When writing the next JSON line would exceed `maxFileSizeMb`:
- If `maxBackupFiles == 0`, the full `audit.log` is deleted and a fresh empty `audit.log` is opened with no backup files created.
- If `maxBackupFiles >= 1`:
  1. If `audit.log.<maxBackupFiles>` exists, it is deleted.
  2. Existing backups are shifted upward from oldest to newest (`audit.log.4 → audit.log.5`, ..., `audit.log.1 → audit.log.2`), preserving any pre-existing numbering gaps without overwriting.
  3. The full active file `audit.log` is moved to `audit.log.1`.
  4. A fresh empty `audit.log` is opened for subsequent events.

---

## 4. Event Catalog

| `event` | Key Fields | Emitted When |
| :--- | :--- | :--- |
| `SERVER_START` | `version`, `bindHost`, `port`, `localLanIp`, `tailscaleIp`, `publicIp`, `aiServer`, `aiStatus` | Server finishes startup and IP/AI discovery |
| `SERVER_STOP` | `reason`, `uptimeMs` | Graceful shutdown (`SHUTDOWN_HOOK`) after draining active sessions |
| `HTTP_ACCESS` | `ip`, `method`, `path`, `status`, `durationMs` | An HTTP request handled by Javalin completes (when `logHttpRequests: true`). WebSocket connections at `/ws` are logged as `WS_CONNECT`/`WS_DISCONNECT` rather than `HTTP_ACCESS`, and malformed URIs rejected early by Jetty (e.g. `..%2F`) do not reach the Javalin logger. |
| `WS_CONNECT` | `sessionId`, `ip` | Client opens `/ws` WebSocket connection |
| `LOBBY_JOIN` | `sessionId`, `ip`, `username` | Client submits a valid username (`JOIN_LOBBY`) |
| `QUEUE_JOIN` | `sessionId`, `ip`, `username`, `position` | Player enters the online matchmaking queue (deduplicated if already queued) |
| `QUEUE_CANCEL` | `sessionId`, `ip`, `username` | Player cancels online matchmaking search or switches from queue directly into another mode |
| `MATCH_START` | `matchId`, `mode`, `playerX`, `ipX`, `playerO`, `ipO`, `firstTurn`, `rematch` | Match starts (`ONLINE_PVP`, `VS_AI`, `LOCAL_PVP`) or restarts via rematch |
| `MATCH_END` | `matchId`, `mode`, `status`, `winner`, `drawReason`, `totalPlies`, `durationMs`, `reason` | Match ends once per round. `status` is `X_WINS`, `O_WINS`, `DRAW`, or `ABANDONED`; `winner` is the winning display name (e.g. `"Alice"`, `"NeuralNetwork AI"`, `"Player X"`, or `null`); `reason` is `COMPLETED`, `PLAYER_LEFT`, `DISCONNECT`, `SWITCH_MODE`, `RESTARTED`, or `SERVER_SHUTDOWN`. |
| `WS_DISCONNECT` | `sessionId`, `ip`, `username`, `durationMs`, `closeStatus` | Client disconnects from `/ws` (preserving RFC 6455 close codes such as `1000`, `1001`, or `1009`) |

### Useful Inspection Commands on Linux

```bash
# Follow live events in terminal
tail -f logs/audit.log

# Pretty-print with jq (if installed)
tail -f logs/audit.log | jq .

# Filter matches or a specific remote IP
grep '"event":"MATCH_START"' logs/audit.log
grep '"ip":"181.' logs/audit.log
```
