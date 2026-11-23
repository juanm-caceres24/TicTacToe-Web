# Configuration, Network Discovery, and AI Client (Stage 2 — `v0.2.0`)

This document covers the external configuration layer (`com.tictactoe.config`), network address discovery (`com.tictactoe.net`), and HTTP/1.1 inference client (`com.tictactoe.ai`) implemented in Stage 2 (`v0.2.0`).

---

## 1. External Configuration (`configs/server.json`) & CLI Overrides

All runtime settings live outside source code in `configs/server.json`:

```json
{
  "host": "0.0.0.0",
  "port": 3000,
  "maxConcurrentMatches": 100,
  "minUsernameLength": 2,
  "maxUsernameLength": 20,
  "repetitionLimit": 3,
  "maxMovementPlies": 50,
  "transport": {
    "maxHttpRequestBytes": 65536,
    "maxWsMessageBytes": 8192,
    "wsIdleTimeoutSeconds": 300
  },
  "logging": {
    "enabled": true,
    "filePath": "logs/audit.log",
    "logHttpRequests": true,
    "trustProxyHeaders": false,
    "maxFileSizeMb": 10,
    "maxBackupFiles": 5
  },
  "publicIpLookup": {
    "enabled": true,
    "url": "https://api.ipify.org",
    "timeoutMs": 2000
  },
  "aiServer": {
    "host": "127.0.0.1",
    "port": 8080,
    "connectTimeoutMs": 2000,
    "requestTimeoutMs": 3000,
    "workerThreads": 4
  }
}
```

- Unknown JSON keys are rejected strictly (`FAIL_ON_UNKNOWN_PROPERTIES = true`) to catch configuration typos early.
- All numeric fields are range-validated inside `ServerConfig` record constructors.

### Supported CLI Flags (`CliArgs`)
- `--config <path>` — Custom configuration file path (default: `configs/server.json`)
- `--host <host>` — Override server bind host
- `--port <port>` — Override server listening port (`0..65535`)
- `--ai-host <host>` — Override external `NeuralNetwork` host
- `--ai-port <port>` — Override external `NeuralNetwork` port (`1..65535`)
- `--log-file <path>` — Override JSONL audit log file path (default: `logs/audit.log`)
- `--no-public-ip` — Disable startup public IP lookup
- `-h`, `--help` — Print usage instructions and exit with code `0`

---

## 2. Startup Network Address Resolution (`com.tictactoe.net`)

`NetworkAddressResolver` inspects active Linux network interfaces and prints a consolidated startup banner via `ResolvedAddresses.formatStartupBanner(...)`:

1. **Local (LAN) IP:** Active non-loopback, non-link-local IPv4 interface excluding container/virtual bridges (`lo`, `docker*`, `br-*`, `veth*`, `virbr*`, `cni*`, `flannel*`, `kube*`, `podman*`, `wg-docker*`) and Tailscale interfaces, preferring `192.168.x.x` over other RFC 1918 subnets (`10.0.0.0/8`, `172.16.0.0/12`).
2. **Tailscale IP:** Active IPv4 address in RFC 6598 CGNAT range **`100.64.0.0/10`** (`100.64.0.0` – `100.127.255.255`), prioritizing interfaces named `tailscale*` or `ts*`.
3. **Public IP:** Optional HTTP `GET` to `publicIpLookup.url` with strict timeout (`publicIpLookup.timeoutMs`) and literal IP validation (rejects HTML error pages or timeouts gracefully, reporting `unavailable` instead of failing startup).

Example console banner:

```text
==========================================================
  TicTacToe-Web Server listening on 0.0.0.0:3000
----------------------------------------------------------
  Local (LAN) : http://192.168.1.42:3000
  Tailscale   : http://100.88.12.5:3000
  Public      : http://181.45.12.90:3000
  AI Server   : http://127.0.0.1:8080 [ONLINE | 9->41]
  Audit Log   : logs/audit.log
==========================================================
```

---

## 3. External `NeuralNetwork` HTTP/1.1 Client (`com.tictactoe.ai`)

`NeuralNetworkClient` uses JDK 21 `java.net.http.HttpClient` explicitly pinned to **`HTTP/1.1`** (avoiding unnecessary `h2c` upgrade headers against lightweight C++ HTTP servers and interoperating cleanly with `NeuralNetwork` `v0.6.0`'s `Connection: close` responses) with independent connect and request timeouts.

### Endpoints Consumed
| Endpoint | Method | Expected Response | Behavior |
| :--- | :--- | :--- | :--- |
| `/health` | `GET` | `200 OK` `{"status":"ok"}` | Verifies server reachability and health |
| `/info` | `GET` | `200 OK` `{"input_size":9,"output_size":41,"num_layers":3,"parameters":23081,"layers":[...]}` | Validates strict `9 → 41` model compatibility (extra `layers` metadata is accepted) |
| `/predict` | `POST` | `200 OK` `{"output":[41 floats],"latency_us":12}` | Sends `{"input":[9 floats],"include_neurons":false}`, masks illegal actions, returns `AiPrediction` |

### Resilience & Error Classification
- `checkStatus()` **never throws**; it returns an `AiStatus` snapshot (`ONLINE`, `INCOMPATIBLE`, `UNHEALTHY`, or `OFFLINE`) so the web UI can display clear notices without crashing the game server.
- `predictMove(GameEngine)` throws typed `AiClientException` with explicit `Reason`:
  - `UNREACHABLE` — connection refused or network I/O failure
  - `TIMEOUT` — `NeuralNetwork` exceeded `requestTimeoutMs`
  - `HTTP_ERROR` — non-200 HTTP response (extracting `{"error":"..."}` when present)
  - `INCOMPATIBLE_MODEL` — output vector length differs from `41`
  - `INVALID_RESPONSE` — malformed JSON or non-numeric output entries
