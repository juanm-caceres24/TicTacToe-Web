# Responsive Web Frontend (Stage 5 — `v0.5.0`)

This document describes the zero-framework vanilla HTML5/CSS3/JavaScript web interface served from classpath `/web` (`src/main/resources/web/`).

---

## 1. Architecture & Design Principles

- **Zero external frontend dependencies:** Pure HTML5 (`index.html`), CSS3 (`styles.css`), and ES6+ JavaScript (`app.js`) with no npm, bundlers, or external CDN fonts — loads instantaneously over offline LAN, Tailscale VPN, or mobile connections.
- **Backend-authoritative UI:** The client never duplicates game rule validation. Every click/tap checks the backend-provided `legalMoves` array and sends `PLACE_PIECE` or `MOVE_PIECE` over WebSockets (`/ws`).
- **XSS-safe DOM rendering:** Usernames and server messages are rendered strictly via `textContent` and `createElement`/`createElementNS` (never `innerHTML`).
- **Automatic protocol detection & reconnect:** Opens `ws://` or `wss://` matching `window.location.protocol` on the same port as HTTP, reconnects with exponential backoff (up to 5 s), automatically re-joins the lobby if a username is active, and cleanly returns to the lobby with a notice if a previous match was closed during the disconnect.
- **Dynamic configuration sync:** Fetches `GET /api/config` on startup so username length bounds (`minUsernameLength`–`maxUsernameLength`), rules summary, and draw messages always reflect `configs/server.json`.

---

## 2. User Flow & Views

1. **Username Screen (`#view-login`):**
   - Prompts for a display name matching the server's configured `minUsernameLength..maxUsernameLength` bounds (cached in `localStorage` for quick re-entry).
   - Displays a concise summary of the two Mobile Tic-Tac-Toe phases and configured draw limits.
2. **Mode Selection Lobby (`#view-lobby`):**
   - **Online Matchmaking:** Enters the FIFO queue (`QUEUE_ONLINE`), displays a live waiting spinner with queue position, and supports cancellation (`CANCEL_QUEUE`).
   - **Play vs NeuralNetwork AI:** Lets the player choose symbol (`X` or `O`) and first mover (`HUMAN` or `AI`), shows live `9 → 41` readiness from `/api/ai/status` with a manual refresh button (`↻`), and displays clear guidance if `NeuralNetwork` is offline or incompatible.
   - **Local Pass & Play:** Starts a same-screen match (`START_LOCAL_GAME`) where both players share one device.
3. **Interactive Game Board (`#view-game`):**
   - **SVG Topology + Touch-Friendly Nodes:** Draws all 16 legal edges (perimeter ring + 8 radial center connections) beneath 9 large circular node buttons aligned to exact SVG coordinates (`50`, `150`, `250` on a `300×300` `viewBox`).
   - **Two-Phase Interaction:**
     - *Phase 1 (`PLACEMENT`):* Legal empty nodes pulse; one tap/click places a piece.
     - *Phase 2 (`MOVEMENT`):* Movable owned pieces are marked selectable; tap 1 selects a piece and animates dashed SVG lines to all legal adjacent destinations; tapping another owned piece switches selection seamlessly; tap 2 slides the piece.
   - **AI Telemetry & Probability Overlay (VS_AI):** Displays `NeuralNetwork` inference latency (`aiMeta.latencyUs`), an optional **Show AI probabilities** toggle (node badges for placement probabilities, weighted SVG lines for movement probabilities, and top-4 move chips), plus a **Retry AI Move** button if a transient AI error occurs.
   - **End-of-Match & Rematch:** Highlights the winning 3-node line, displays win/draw reasons (including dynamic `repetitionLimit` and `maxMovementPlies`), handles post-game mutual rematch voting in Online PvP, and surfaces opponent disconnect notifications cleanly.
