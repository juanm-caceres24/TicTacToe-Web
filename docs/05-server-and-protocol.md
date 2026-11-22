# Web Server, Matchmaking & Real-Time Protocol (Stage 4 — `v0.4.0`)

This document describes the standalone Javalin 6 server (`com.tictactoe.web`), concurrent match coordinator (`com.tictactoe.match`), and HTTP + WebSocket protocol implemented in Stage 4 (`v0.4.0`).

---

## 1. Running the Server

```bash
# Build shaded executable JAR and run all tests (0 warnings)
./scripts/build.sh --clean

# Launch using configs/server.json
./scripts/run.sh

# Launch with CLI overrides
./scripts/run.sh --port 3000 --ai-host 127.0.0.1 --ai-port 8080

# Show CLI help
./scripts/run.sh --help

# Run automated curl smoke test against the packaged shaded JAR
./scripts/test_api.sh
```

At startup, `Main` prints a consolidated banner containing the **Local (LAN) IP**, **Tailscale IP (`100.64.0.0/10`)**, **Public IP** with the active port, and live health/compatibility status of `NeuralNetwork`. A JVM shutdown hook guarantees clean shutdown on `SIGINT` / `SIGTERM`.

---

## 2. Single-Port HTTP REST + WebSocket Architecture

Static web files, REST endpoints, and WebSockets all share the **same configured TCP port**, so only one port-forwarding rule is needed on the router.

### HTTP REST Endpoints (`RestController`)
| Endpoint | Method | Response |
| :--- | :--- | :--- |
| `/` | `GET` | Serves static frontend files from classpath `/web` |
| `/api/health` | `GET` | `{"status":"ok","version":"0.5.0","connectedSessions":N,"queueSize":Q,"activeMatches":M}` |
| `/api/ai/status` | `GET` | Live `NeuralNetwork` health and `9 → 41` compatibility snapshot (`available`, `inputSize`, `outputSize`, `summary`, `error`) |

### WebSocket Protocol (`GET /ws`, JSON text frames)

Clients never connect peer-to-peer; every browser communicates solely with the Javalin server over WebSocket JSON text frames (upgraded from HTTP/1.1 on `/ws`, max text frame `8 KB`, idle timeout `5 min`).

#### Client → Server Messages
| `type` | Fields | Behavior |
| :--- | :--- | :--- |
| `JOIN_LOBBY` | `username` | Validates username (`2..maxUsernameLength`, XSS-safe whitelist) and returns `WELCOME` |
| `QUEUE_ONLINE` | — | Joins FIFO matchmaking queue; pairs immediately if an open opponent is waiting |
| `CANCEL_QUEUE` | — | Leaves matchmaking queue (`QUEUE_CANCELLED`) |
| `START_AI_GAME` | `playerSymbol` (`"X"`/`"O"`), `firstTurn` (`"HUMAN"`/`"AI"`) | Verifies `NeuralNetwork` is ready (`9 → 41`), starts match, triggers AI move asynchronously if AI starts |
| `START_LOCAL_GAME` | — | Starts a same-screen Local PvP match (`yourSymbol: "BOTH"`) |
| `PLACE_PIECE` | `to` (`0..8`) | Applies Phase 1 placement on the backend `GameEngine` |
| `MOVE_PIECE` | `from` (`0..8`), `to` (`0..8`) | Applies Phase 2 slide movement on the backend `GameEngine` |
| `PLAY_AGAIN` | — | Immediate reset in `VS_AI` / `LOCAL_PVP`; mutual rematch vote with alternating starter in `ONLINE_PVP` |
| `RETRY_AI_MOVE` | — | Retries an AI turn if a transient `NeuralNetwork` error occurred |
| `LEAVE_MATCH` | — | Leaves current match and notifies online opponent (`OPPONENT_LEFT`) |

#### Server → Client Messages
- `WELCOME`: `{type, sessionId, username, aiStatus}`
- `QUEUE_JOINED`: `{type, position}`
- `QUEUE_CANCELLED`: `{type}`
- `MATCH_FOUND`: `{type, matchId, mode, yourSymbol, opponent, playerX, playerO}`
- `GAME_STATE`: `{type, matchId, mode, board[9], phase, currentTurn, piecesPlaced, legalMoves[{from,to,actionIndex}], status, drawReason, winner, winningLine, lastMove, playerX, playerO, movementPlies, maxMovementPlies, aiThinking, aiMeta}`
- `REMATCH_REQUESTED`: `{type, matchId, byUsername}`
- `OPPONENT_LEFT`: `{type, matchId, message}`
- `MATCH_LEFT`: `{type, matchId}`
- `ERROR`: `{type, code, message}`

---

## 3. Concurrency & Resilience Guarantees

- **Per-match locking:** Each `GameMatch` synchronizes its own `GameEngine`, preventing contention across concurrent matches.
- **Non-blocking AI turns:** `MatchManager` runs `NeuralNetworkClient.predictMove(...)` on a dedicated daemon worker pool (`ai-worker-*`) using an engine snapshot copied outside the HTTP call, broadcasting `aiThinking: true` immediately and `aiMeta` upon completion.
- **Thread-safe WebSocket sends:** Outbound frames per session are serialized through a per-session lock to prevent concurrent Jetty write races.
- **Disconnect cleanup:** Closing a browser tab removes the player from `MatchmakingQueue`, closes any active match, frees memory, and immediately delivers `OPPONENT_LEFT` to an online opponent.
