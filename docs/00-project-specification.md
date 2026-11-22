# Project Specification — TicTacToe-Web

> **Single Source of Truth** for architecture, game rules, AI integration contract, network protocol, and milestone status. Updated at the end of every milestone.

---

## 1. Overview

**TicTacToe-Web** is a standalone Java 21 web server (built with Javalin and Maven) hosting a responsive browser-based **Mobile Tic-Tac-Toe** (*Three Men's Morris* with radial center connections) playable from desktop and mobile devices across LAN, Tailscale VPN, or public port-forwarded networks.

All game logic and validation run exclusively on the backend. The frontend is zero-framework vanilla HTML5/CSS3/JavaScript in English.

### Supported Game Modes
1. **Online PvP (Matchmaking Queue):** Players enter a username, join a FIFO waiting queue, and are paired in real time over WebSockets.
2. **Player vs AI (`NeuralNetwork`):** Players choose their symbol (`X` or `O`) and who moves first (`HUMAN` or `AI`). The backend requests moves from an external HTTP inference server (`NeuralNetwork`).
3. **Local PvP (Same Screen):** Two players take turns on the same device through the same backend engine.

---

## 2. Game Rules (Mobile Tic-Tac-Toe / 3 Pieces per Player)

The board has 9 cells indexed in row-major order (`0..8`):

```text
0 — 1 — 2
| \ | / |
3 — 4 — 5
| / | \ |
6 — 7 — 8
```

### Phases
1. **Phase 1 — Placement (`PLACEMENT`):**
   - Active while the current player has fewer than `3` pieces on the board.
   - A legal move places one piece on any empty cell (`0..8`).
   - A player may win during Phase 1 (as early as ply 5 or 6) by completing 3 in a row.
2. **Phase 2 — Movement (`MOVEMENT`):**
   - Active once the current player already has `3` pieces on the board (both players have 3 pieces starting at ply 7).
   - A legal move selects one owned piece at `from` and slides it to an adjacent empty cell `to`:
     - **Perimeter ring:** `0 ↔ 1 ↔ 2 ↔ 5 ↔ 8 ↔ 7 ↔ 6 ↔ 3 ↔ 0`.
     - **Center connections:** Center cell `4` connects bidirectionally to all 8 perimeter cells (`0, 1, 2, 3, 5, 6, 7, 8`).

### Victory & Draw Conditions
- **Victory:** First player to align 3 pieces on any of the 8 standard lines (3 rows, 3 columns, 2 diagonals). Victory always takes precedence over draw checks.
- **Draw by Repetition:** Default limit `3` occurrences of the exact same `(board, currentTurn)` position during the movement phase (`THREEFOLD_REPETITION`).
- **Draw by Move Limit:** Default limit `50` movement plies without a winner (`MOVE_LIMIT_REACHED`). Both limits are configurable.

---

## 3. External `NeuralNetwork` Integration Contract

The TicTacToe-Web server acts as an HTTP/1.1 + JSON client against the external `NeuralNetwork` inference server (default `127.0.0.1:8080`, configurable via `configs/server.json` and CLI flags).

### Expected Endpoints on `NeuralNetwork`
- `GET /health` → `200 OK` `{"status":"ok"}`
- `GET /info` → `200 OK` `{"input_size": 9, "output_size": 41, ...}`
- `POST /predict` → body `{"input": [9 floats], "include_neurons": false}` → `200 OK` `{"output": [41 floats], "latency_us": 12}`

### Action Space Encoding (`9 → 41` Policy Network)
- **Input (`9` floats, player-relative):** `+1.0` = current player (AI), `-1.0` = opponent, `0.0` = empty. The phase is implicitly encoded by the count of `+1.0` entries (`< 3` → `PLACEMENT`, `== 3` → `MOVEMENT`).
- **Output (`41` logits/probabilities):**
  - `0..8`: Placement on cell `i`.
  - `9..40`: The `32` directed legal movement edges `(from → to)` sorted lexicographically by `(from, to)`.
- **Legal Move Masking:** The backend masks out all illegal indices before selecting `argmax`, guaranteeing that the AI can never execute an illegal move even if the network outputs invalid probabilities or `NaN`.

---

## 4. Technical Stack & Constraints

- **Language & Runtime:** Java 21 (OpenJDK 21), Maven 3.9+, strict compilation (`-Xlint:all -Werror`, 0 warnings).
- **Language Policy:** All source code, Javadocs, comments, commits, scripts, documentation, and UI text are in **English**.
- **Dependencies (Minimal / YAGNI):**
  - Stage 1: Pure JDK 21 runtime (0 runtime dependencies), JUnit 5 for testing.
  - Stage 2: Jackson Databind (JSON config & AI HTTP client using JDK `java.net.http.HttpClient`).
  - Stage 4: Javalin 6 + `slf4j-simple` (HTTP static hosting, REST endpoints, and WebSockets on a single port).
- **Network Startup Banner (Stage 2 / Stage 4):** Prints Local LAN IP, Tailscale IP (`100.64.0.0/10`), Public IP, and configured port at startup.
- **Externalized Configuration:** Server bind settings, gameplay limits (`minUsernameLength`, `maxUsernameLength`, `repetitionLimit`, `maxMovementPlies`), HTTP/WebSocket transport limits, JSONL audit logging settings (`logging`), public IP lookup settings, and AI server parameters (`host`, `port`, timeouts, `workerThreads`) are loaded from `configs/server.json` and exposed to the frontend via `GET /api/config`. Key launch flags can be overridden via CLI (`--config`, `--host`, `--port`, `--ai-host`, `--ai-port`, `--log-file`, `--no-public-ip`, `-h`, `--help`).

---

## 5. Communication Protocol

- **HTTP REST (same port):**
  - `GET /` → static web frontend (`index.html`, `styles.css`, `app.js`).
  - `GET /api/health` → server health, build version, connected sessions, queue size, and active matches.
  - `GET /api/config` → public gameplay bounds (`version`, `minUsernameLength`, `maxUsernameLength`, `repetitionLimit`, `maxMovementPlies`) synchronized with `configs/server.json`.
  - `GET /api/ai/status` → live health and `9 → 41` model compatibility status of `NeuralNetwork`.
- **WebSocket (`GET /ws`, HTTP Upgrade on the same port):**
  - Bidirectional JSON text frames between each browser and the Java server (client-server, never P2P).
  - Client → Server: `JOIN_LOBBY`, `QUEUE_ONLINE`, `CANCEL_QUEUE`, `START_AI_GAME`, `START_LOCAL_GAME`, `PLACE_PIECE`, `MOVE_PIECE`, `PLAY_AGAIN`, `RETRY_AI_MOVE`, `LEAVE_MATCH`.
  - Server → Client: `WELCOME`, `QUEUE_JOINED`, `QUEUE_CANCELLED`, `MATCH_FOUND`, `GAME_STATE` (including backend-computed `legalMoves`, `repetitionLimit`, and optional AI probability overlay), `REMATCH_REQUESTED`, `OPPONENT_LEFT`, `MATCH_LEFT`, `ERROR`.

---

## 6. Milestone Roadmap & Current Status

| Milestone | Tag | Scope | Status |
| :--- | :--- | :--- | :--- |
| **Stage 1** | `v0.1.0` | Repo setup, Maven strict build, Git workflow docs, pure Mobile Tic-Tac-Toe game engine + `9 → 41` move encoder + JUnit 5 tests | **Completed** |
| **Stage 2** | `v0.2.0` | External `configs/server.json`, CLI parser (`--help`), LAN/Tailscale/Public IP resolver, HTTP `NeuralNetworkClient` + mock HTTP tests | **Completed** |
| **Stage 3** | `v0.3.0` | Exact Retrograde Analysis solver (cycle-free optimal tablebase), `DatasetGenMain` CSV exporter (`9 + 41`), `configs/tictactoe_nn_config.json` | **Completed** |
| **Stage 4** | `v0.4.0` | Javalin 6 server, FIFO Online Matchmaking, concurrent `MatchManager`, async AI turns, REST + WebSocket `/ws` + E2E tests | **Completed** |
| **Stage 5** | `v0.5.0` | Responsive vanilla HTML5/CSS3/JS frontend (desktop + mobile touch), SVG topology board, AI latency/probability overlay, final E2E verification | **Completed** |
| **Hardening** | `v0.5.1` | Audit fixes (WS reconnect recovery, `MoveEncoder` finite masking, IPv6 validation, `/api/config` frontend sync, transport & worker config, expanded test coverage) | **Completed** |
| **Audit Logging** | `v0.6.0` | File-only JSON Lines (`logs/audit.log`) audit trail with ISO-8601 timestamps, rolling size-based log rotation, server lifecycle, client IP, match mode & disconnect auditing | **Completed** |
