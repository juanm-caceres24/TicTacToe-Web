(() => {
    "use strict";

    const SVG_NS = "http://www.w3.org/2000/svg";
    const STORAGE_USERNAME_KEY = "ttt_username";

    // Board node coordinates inside the 300x300 SVG viewBox.
    const NODE_COORDS = [
        { x: 50, y: 50 },   // 0
        { x: 150, y: 50 },  // 1
        { x: 250, y: 50 },  // 2
        { x: 50, y: 150 },  // 3
        { x: 150, y: 150 }, // 4 (center)
        { x: 250, y: 150 }, // 5
        { x: 50, y: 250 },  // 6
        { x: 150, y: 250 }, // 7
        { x: 250, y: 250 }  // 8
    ];

    // Undirected board edges matching BoardTopology (perimeter ring + 8 radial center edges).
    const BOARD_EDGES = [
        [0, 1], [1, 2], [2, 5], [5, 8], [8, 7], [7, 6], [6, 3], [3, 0],
        [0, 4], [1, 4], [2, 4], [3, 4], [5, 4], [6, 4], [7, 4], [8, 4]
    ];

    // Canonical 41-action lookup used solely to display AI probability overlays.
    const ADJACENCY = [
        [1, 3, 4],
        [0, 2, 4],
        [1, 4, 5],
        [0, 4, 6],
        [0, 1, 2, 3, 5, 6, 7, 8],
        [2, 4, 8],
        [3, 4, 7],
        [4, 6, 8],
        [4, 5, 7]
    ];

    const ACTION_TABLE = (() => {
        const table = [];
        for (let cell = 0; cell < 9; cell++) {
            table.push({ from: -1, to: cell });
        }
        for (let from = 0; from < 9; from++) {
            for (const to of ADJACENCY[from]) {
                table.push({ from, to });
            }
        }
        return table;
    })();

    // Application state
    const appState = {
        ws: null,
        wsConnected: false,
        reconnectDelayMs: 1000,
        reconnectTimer: null,
        sessionId: null,
        username: null,
        publicConfig: {
            minUsernameLength: 2,
            maxUsernameLength: 20,
            repetitionLimit: 3,
            maxMovementPlies: 50
        },
        aiStatus: null,
        aiConfig: {
            playerSymbol: "X",
            firstTurn: "HUMAN"
        },
        inQueue: false,
        matchMeta: null,   // { matchId, mode, yourSymbol, opponent }
        gameState: null,   // Latest GAME_STATE payload from backend
        selectedFrom: null,
        showAiProbs: false,
        opponentDisconnected: false,
        rematchRequestedByMe: false,
        aiFailedOnTurn: false,
        toastTimer: null
    };

    // DOM references
    const el = {
        wsPill: document.getElementById("ws-status-pill"),
        aiPill: document.getElementById("ai-status-pill"),
        btnRefreshAi: document.getElementById("btn-refresh-ai"),
        toast: document.getElementById("toast-banner"),

        viewLogin: document.getElementById("view-login"),
        viewLobby: document.getElementById("view-lobby"),
        viewGame: document.getElementById("view-game"),

        loginForm: document.getElementById("login-form"),
        usernameInput: document.getElementById("username-input"),
        usernameRange: document.getElementById("username-range"),
        ruleRepetitionLimit: document.getElementById("rule-repetition-limit"),
        ruleMaxPlies: document.getElementById("rule-max-plies"),
        btnJoinLobby: document.getElementById("btn-join-lobby"),

        lobbyUsername: document.getElementById("lobby-username"),
        btnChangeName: document.getElementById("btn-change-name"),
        queueIdlePanel: document.getElementById("queue-idle-panel"),
        queueWaitingPanel: document.getElementById("queue-waiting-panel"),
        queuePositionText: document.getElementById("queue-position-text"),
        btnQueueOnline: document.getElementById("btn-queue-online"),
        btnCancelQueue: document.getElementById("btn-cancel-queue"),
        aiLobbyNotice: document.getElementById("ai-lobby-notice"),
        btnStartAi: document.getElementById("btn-start-ai"),
        btnStartLocal: document.getElementById("btn-start-local"),

        cardPlayerX: document.getElementById("card-player-x"),
        cardPlayerO: document.getElementById("card-player-o"),
        namePlayerX: document.getElementById("name-player-x"),
        namePlayerO: document.getElementById("name-player-o"),
        piecesPlayerX: document.getElementById("pieces-player-x"),
        piecesPlayerO: document.getElementById("pieces-player-o"),
        phaseBadge: document.getElementById("phase-badge"),
        turnText: document.getElementById("turn-text"),
        plyCounter: document.getElementById("ply-counter"),

        svgEdgesBase: document.getElementById("svg-edges-base"),
        svgAiOverlay: document.getElementById("svg-ai-overlay"),
        svgLastMove: document.getElementById("svg-last-move"),
        svgEdgesLegal: document.getElementById("svg-edges-legal"),
        svgWinningLine: document.getElementById("svg-winning-line"),
        boardNodes: document.getElementById("board-nodes"),

        aiTelemetryPanel: document.getElementById("ai-telemetry-panel"),
        aiLatencyValue: document.getElementById("ai-latency-value"),
        toggleAiProbs: document.getElementById("toggle-ai-probs"),
        aiTopMoves: document.getElementById("ai-top-moves"),
        aiRetryRow: document.getElementById("ai-retry-row"),
        btnRetryAi: document.getElementById("btn-retry-ai"),

        btnPlayAgain: document.getElementById("btn-play-again"),
        btnLeaveMatch: document.getElementById("btn-leave-match"),
        rematchHint: document.getElementById("rematch-hint")
    };

    const nodeButtons = [];

    function init() {
        buildBoardStructure();
        bindEvents();

        const savedName = localStorage.getItem(STORAGE_USERNAME_KEY);
        if (savedName) {
            el.usernameInput.value = savedName;
        }

        loadPublicConfig();
        refreshAiStatus();
        connectWebSocket();
    }

    function buildBoardStructure() {
        // Draw static board edges in SVG.
        for (const [u, v] of BOARD_EDGES) {
            const line = createSvgElement("line", {
                x1: NODE_COORDS[u].x,
                y1: NODE_COORDS[u].y,
                x2: NODE_COORDS[v].x,
                y2: NODE_COORDS[v].y,
                class: "edge-base"
            });
            el.svgEdgesBase.appendChild(line);
        }

        // Create 9 interactive node buttons aligned with SVG intersections.
        for (let i = 0; i < 9; i++) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "board-node";
            btn.dataset.cell = String(i);
            btn.style.left = `${(NODE_COORDS[i].x / 300) * 100}%`;
            btn.style.top = `${(NODE_COORDS[i].y / 300) * 100}%`;
            btn.setAttribute("aria-label", `Cell ${i}`);
            btn.addEventListener("click", () => handleNodeClick(i));
            el.boardNodes.appendChild(btn);
            nodeButtons.push(btn);
        }
    }

    function bindEvents() {
        el.btnRefreshAi.addEventListener("click", () => {
            refreshAiStatus(true);
        });

        el.loginForm.addEventListener("submit", (event) => {
            event.preventDefault();
            const raw = el.usernameInput.value.trim();
            const minLen = appState.publicConfig.minUsernameLength;
            const maxLen = appState.publicConfig.maxUsernameLength;
            if (raw.length < minLen || raw.length > maxLen) {
                showToast(`Username must be between ${minLen} and ${maxLen} characters.`, "error");
                return;
            }
            if (!appState.wsConnected) {
                showToast("Not connected to server yet. Please wait...", "error");
                return;
            }
            sendWs({ type: "JOIN_LOBBY", username: raw });
        });

        el.btnChangeName.addEventListener("click", () => {
            if (appState.inQueue) {
                sendWs({ type: "CANCEL_QUEUE" });
            }
            appState.inQueue = false;
            updateQueueUi();
            showView("login");
            el.usernameInput.focus();
        });

        el.btnQueueOnline.addEventListener("click", () => {
            sendWs({ type: "QUEUE_ONLINE" });
        });

        el.btnCancelQueue.addEventListener("click", () => {
            sendWs({ type: "CANCEL_QUEUE" });
        });

        document.querySelectorAll("[data-ai-symbol]").forEach((btn) => {
            btn.addEventListener("click", () => {
                appState.aiConfig.playerSymbol = btn.dataset.aiSymbol;
                document.querySelectorAll("[data-ai-symbol]").forEach((b) => b.classList.remove("active"));
                btn.classList.add("active");
            });
        });

        document.querySelectorAll("[data-ai-first]").forEach((btn) => {
            btn.addEventListener("click", () => {
                appState.aiConfig.firstTurn = btn.dataset.aiFirst;
                document.querySelectorAll("[data-ai-first]").forEach((b) => b.classList.remove("active"));
                btn.classList.add("active");
            });
        });

        el.btnStartAi.addEventListener("click", () => {
            sendWs({
                type: "START_AI_GAME",
                playerSymbol: appState.aiConfig.playerSymbol,
                firstTurn: appState.aiConfig.firstTurn
            });
        });

        el.btnStartLocal.addEventListener("click", () => {
            sendWs({ type: "START_LOCAL_GAME" });
        });

        el.toggleAiProbs.addEventListener("change", () => {
            appState.showAiProbs = el.toggleAiProbs.checked;
            renderGame();
        });

        el.btnRetryAi.addEventListener("click", () => {
            appState.aiFailedOnTurn = false;
            el.aiRetryRow.classList.add("hidden");
            sendWs({ type: "RETRY_AI_MOVE" });
        });

        el.btnPlayAgain.addEventListener("click", () => {
            if (appState.opponentDisconnected) {
                return;
            }
            if (appState.matchMeta && appState.matchMeta.mode === "ONLINE_PVP") {
                appState.rematchRequestedByMe = true;
                el.rematchHint.textContent = "Rematch requested. Waiting for opponent...";
                el.rematchHint.classList.remove("hidden");
                el.btnPlayAgain.disabled = true;
            }
            sendWs({ type: "PLAY_AGAIN" });
        });

        el.btnLeaveMatch.addEventListener("click", () => {
            if (!appState.opponentDisconnected && appState.matchMeta) {
                sendWs({ type: "LEAVE_MATCH" });
            }
            resetMatchState();
            showView("lobby");
            refreshAiStatus();
        });
    }

    // WebSocket lifecycle
    function connectWebSocket() {
        if (appState.reconnectTimer) {
            clearTimeout(appState.reconnectTimer);
            appState.reconnectTimer = null;
        }

        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        const wsUrl = `${protocol}//${window.location.host}/ws`;
        setWsPill("Connecting...", "pill-neutral");

        const ws = new WebSocket(wsUrl);
        appState.ws = ws;

        ws.addEventListener("open", () => {
            appState.wsConnected = true;
            appState.reconnectDelayMs = 1000;
            setWsPill("Connected", "pill-ok");

            // Automatically re-identify in the lobby after a transient network drop.
            if (appState.username) {
                sendWs({ type: "JOIN_LOBBY", username: appState.username });
            }
        });

        ws.addEventListener("message", (event) => {
            let msg;
            try {
                msg = JSON.parse(event.data);
            } catch {
                return;
            }
            handleServerMessage(msg);
        });

        ws.addEventListener("close", () => {
            appState.wsConnected = false;
            setWsPill("Reconnecting...", "pill-warn");
            if (appState.inQueue) {
                appState.inQueue = false;
                updateQueueUi();
            }
            scheduleReconnect();
        });

        ws.addEventListener("error", () => {
            setWsPill("Connection Error", "pill-err");
        });
    }

    function scheduleReconnect() {
        if (appState.reconnectTimer) {
            return;
        }
        const delay = appState.reconnectDelayMs;
        appState.reconnectDelayMs = Math.min(appState.reconnectDelayMs * 2, 5000);
        appState.reconnectTimer = setTimeout(() => {
            appState.reconnectTimer = null;
            connectWebSocket();
        }, delay);
    }

    function sendWs(payload) {
        if (!appState.ws || appState.ws.readyState !== WebSocket.OPEN) {
            showToast("Connection lost. Reconnecting to server...", "error");
            return false;
        }
        appState.ws.send(JSON.stringify(payload));
        return true;
    }

    async function loadPublicConfig() {
        try {
            const response = await fetch("/api/config", { cache: "no-store" });
            if (!response.ok) {
                return;
            }
            const cfg = await response.json();
            applyPublicConfig(cfg);
        } catch {
            // Keep default bounds when offline.
        }
    }

    function applyPublicConfig(cfg) {
        if (!cfg) {
            return;
        }
        if (Number.isInteger(cfg.minUsernameLength) && Number.isInteger(cfg.maxUsernameLength)) {
            appState.publicConfig.minUsernameLength = cfg.minUsernameLength;
            appState.publicConfig.maxUsernameLength = cfg.maxUsernameLength;
            el.usernameInput.maxLength = cfg.maxUsernameLength;
            el.usernameRange.textContent = `${cfg.minUsernameLength}–${cfg.maxUsernameLength}`;
        }
        if (Number.isInteger(cfg.repetitionLimit)) {
            appState.publicConfig.repetitionLimit = cfg.repetitionLimit;
            el.ruleRepetitionLimit.textContent = String(cfg.repetitionLimit);
        }
        if (Number.isInteger(cfg.maxMovementPlies)) {
            appState.publicConfig.maxMovementPlies = cfg.maxMovementPlies;
            el.ruleMaxPlies.textContent = String(cfg.maxMovementPlies);
        }
    }

    async function refreshAiStatus(showFeedback = false) {
        try {
            const response = await fetch("/api/ai/status", { cache: "no-store" });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const status = await response.json();
            applyAiStatus(status);
            if (showFeedback) {
                showToast(
                    status.available ? "NeuralNetwork AI is online and ready." : `AI status: ${status.summary}`,
                    status.available ? "success" : "info"
                );
            }
        } catch {
            applyAiStatus({
                available: false,
                serverReachable: false,
                healthy: false,
                compatible: false,
                summary: "Server unreachable",
                error: "Could not reach /api/ai/status"
            });
        }
    }

    function applyAiStatus(status) {
        appState.aiStatus = status;
        if (!status) {
            return;
        }

        if (status.available) {
            el.aiPill.textContent = `AI: Online (${status.inputSize}→${status.outputSize})`;
            el.aiPill.className = "pill pill-ok";
            el.btnStartAi.disabled = false;
            el.aiLobbyNotice.classList.add("hidden");
            el.aiLobbyNotice.textContent = "";
        } else if (status.serverReachable && status.healthy && !status.compatible) {
            el.aiPill.textContent = `AI: Incompatible (${status.inputSize}→${status.outputSize})`;
            el.aiPill.className = "pill pill-warn";
            el.btnStartAi.disabled = true;
            el.aiLobbyNotice.textContent = status.error || "NeuralNetwork model must have 9 inputs and 41 outputs.";
            el.aiLobbyNotice.classList.remove("hidden");
        } else {
            el.aiPill.textContent = "AI: Offline";
            el.aiPill.className = "pill pill-err";
            el.btnStartAi.disabled = true;
            el.aiLobbyNotice.textContent = `External NeuralNetwork server is not available (${status.summary || "offline"}). Start NeuralNetwork and click ↻ to retry.`;
            el.aiLobbyNotice.classList.remove("hidden");
        }
    }

    // Server message routing
    function handleServerMessage(msg) {
        switch (msg.type) {
            case "WELCOME": {
                const hadActiveMatch = appState.matchMeta !== null;
                appState.sessionId = msg.sessionId;
                appState.username = msg.username;
                localStorage.setItem(STORAGE_USERNAME_KEY, msg.username);
                el.lobbyUsername.textContent = msg.username;
                if (msg.aiStatus) {
                    applyAiStatus(msg.aiStatus);
                }
                if (hadActiveMatch) {
                    // Server closed the previous match when the prior WebSocket disconnected.
                    resetMatchState();
                    showToast("Connection was interrupted; previous match ended. Returned to lobby.", "info");
                }
                showView("lobby");
                break;
            }

            case "QUEUE_JOINED": {
                appState.inQueue = true;
                el.queuePositionText.textContent = `Queue position #${msg.position}`;
                updateQueueUi();
                break;
            }

            case "QUEUE_CANCELLED": {
                appState.inQueue = false;
                updateQueueUi();
                break;
            }

            case "MATCH_FOUND": {
                appState.inQueue = false;
                updateQueueUi();
                appState.matchMeta = {
                    matchId: msg.matchId,
                    mode: msg.mode,
                    yourSymbol: msg.yourSymbol,
                    opponent: msg.opponent
                };
                appState.gameState = null;
                appState.selectedFrom = null;
                appState.opponentDisconnected = false;
                appState.rematchRequestedByMe = false;
                appState.aiFailedOnTurn = false;
                el.rematchHint.classList.add("hidden");
                el.aiRetryRow.classList.add("hidden");
                el.btnPlayAgain.disabled = msg.mode === "ONLINE_PVP";

                showView("game");
                if (msg.mode === "ONLINE_PVP") {
                    showToast(`Matched against ${msg.opponent}! You play as ${msg.yourSymbol}.`, "success");
                }
                break;
            }

            case "GAME_STATE": {
                appState.gameState = msg;
                appState.selectedFrom = null;
                appState.aiFailedOnTurn = false;
                el.aiRetryRow.classList.add("hidden");

                const isOnline = appState.matchMeta && appState.matchMeta.mode === "ONLINE_PVP";
                if (msg.status === "IN_PROGRESS") {
                    if (appState.rematchRequestedByMe) {
                        appState.rematchRequestedByMe = false;
                        el.rematchHint.classList.add("hidden");
                    }
                    el.btnPlayAgain.disabled = isOnline || appState.opponentDisconnected;
                } else if (!appState.opponentDisconnected) {
                    el.btnPlayAgain.disabled = appState.rematchRequestedByMe;
                }

                renderGame();
                break;
            }

            case "REMATCH_REQUESTED": {
                el.rematchHint.textContent = `${msg.byUsername} wants a rematch! Click Play Again to accept.`;
                el.rematchHint.classList.remove("hidden");
                showToast(`${msg.byUsername} requested a rematch.`, "info");
                break;
            }

            case "OPPONENT_LEFT": {
                appState.opponentDisconnected = true;
                appState.selectedFrom = null;
                el.btnPlayAgain.disabled = true;
                el.rematchHint.textContent = msg.message || "Your opponent left the match.";
                el.rematchHint.classList.remove("hidden");
                showToast(msg.message || "Your opponent disconnected.", "error");
                renderGame();
                break;
            }

            case "MATCH_LEFT": {
                resetMatchState();
                showView("lobby");
                break;
            }

            case "ERROR": {
                showToast(msg.message || `Error: ${msg.code}`, "error");
                if (msg.code === "AI_UNAVAILABLE") {
                    refreshAiStatus();
                } else if (msg.code === "AI_ERROR") {
                    appState.aiFailedOnTurn = true;
                    el.aiRetryRow.classList.remove("hidden");
                }
                break;
            }

            default:
                break;
        }
    }

    // Board interaction (strictly guided by backend legalMoves)
    function isLocalUserTurn() {
        const gs = appState.gameState;
        const meta = appState.matchMeta;
        if (!gs || !meta || gs.status !== "IN_PROGRESS" || gs.aiThinking || appState.opponentDisconnected) {
            return false;
        }
        if (meta.mode === "LOCAL_PVP") {
            return true;
        }
        return gs.currentTurn === meta.yourSymbol;
    }

    function handleNodeClick(cell) {
        if (!isLocalUserTurn()) {
            return;
        }
        const gs = appState.gameState;

        if (gs.phase === "PLACEMENT") {
            const isLegal = gs.legalMoves.some((m) => m.from === -1 && m.to === cell);
            if (isLegal) {
                sendWs({ type: "PLACE_PIECE", to: cell });
            }
            return;
        }

        // Phase 2: MOVEMENT
        const currentPiece = gs.board[cell];
        const hasMovesFromCell = gs.legalMoves.some((m) => m.from === cell);

        if (appState.selectedFrom === null) {
            if (currentPiece === gs.currentTurn && hasMovesFromCell) {
                appState.selectedFrom = cell;
                renderBoard();
            }
            return;
        }

        // Clicking the already selected piece deselects it.
        if (appState.selectedFrom === cell) {
            appState.selectedFrom = null;
            renderBoard();
            return;
        }

        // Clicking another owned movable piece switches selection seamlessly.
        if (currentPiece === gs.currentTurn && hasMovesFromCell) {
            appState.selectedFrom = cell;
            renderBoard();
            return;
        }

        // Clicking a legal destination completes the slide move.
        const isLegalSlide = gs.legalMoves.some(
            (m) => m.from === appState.selectedFrom && m.to === cell
        );
        if (isLegalSlide) {
            const fromCell = appState.selectedFrom;
            appState.selectedFrom = null;
            sendWs({ type: "MOVE_PIECE", from: fromCell, to: cell });
        }
    }

    // Rendering
    function renderGame() {
        const gs = appState.gameState;
        const meta = appState.matchMeta;
        if (!gs || !meta) {
            return;
        }

        // Scoreboard
        el.namePlayerX.textContent = gs.playerX;
        el.namePlayerO.textContent = gs.playerO;
        el.piecesPlayerX.textContent = `${gs.piecesPlaced.X} / 3 placed`;
        el.piecesPlayerO.textContent = `${gs.piecesPlaced.O} / 3 placed`;

        const activeInProgress = gs.status === "IN_PROGRESS" && !appState.opponentDisconnected;
        el.cardPlayerX.classList.toggle("active-turn", activeInProgress && gs.currentTurn === "X");
        el.cardPlayerO.classList.toggle("active-turn", activeInProgress && gs.currentTurn === "O");

        // Phase & turn banner
        updateTurnBanner(gs, meta);

        // Movement ply counter
        if (gs.phase === "MOVEMENT" || gs.movementPlies > 0) {
            el.plyCounter.textContent = `Moves: ${gs.movementPlies} / ${gs.maxMovementPlies}`;
            el.plyCounter.classList.remove("hidden");
        } else {
            el.plyCounter.classList.add("hidden");
        }

        // AI telemetry panel
        if (meta.mode === "VS_AI") {
            el.aiTelemetryPanel.classList.remove("hidden");
            if (gs.aiMeta && gs.aiMeta.latencyUs >= 0) {
                el.aiLatencyValue.textContent = `${gs.aiMeta.latencyUs} µs`;
            } else if (gs.aiThinking) {
                el.aiLatencyValue.textContent = "Computing...";
            } else {
                el.aiLatencyValue.textContent = "—";
            }
            renderAiTopMoves(gs);
        } else {
            el.aiTelemetryPanel.classList.add("hidden");
        }

        renderBoard();
    }

    function updateTurnBanner(gs, meta) {
        el.phaseBadge.className = "phase-badge";

        if (appState.opponentDisconnected) {
            el.phaseBadge.textContent = "Ended";
            el.phaseBadge.classList.add("phase-ended");
            el.turnText.textContent = "Opponent left the match.";
            return;
        }

        if (gs.status === "X_WINS" || gs.status === "O_WINS") {
            el.phaseBadge.textContent = "Victory";
            el.phaseBadge.classList.add("phase-ended");
            const winnerName = gs.winner === "X" ? gs.playerX : gs.playerO;
            el.turnText.textContent = `${winnerName} (${gs.winner}) wins!`;
            return;
        }

        if (gs.status === "DRAW") {
            el.phaseBadge.textContent = "Draw";
            el.phaseBadge.classList.add("phase-ended");
            const repLimit = Number.isInteger(gs.repetitionLimit)
                ? gs.repetitionLimit
                : appState.publicConfig.repetitionLimit;
            const reasonLabel = gs.drawReason === "THREEFOLD_REPETITION"
                ? `${repLimit}x repeated position`
                : `Movement ply limit reached (${gs.maxMovementPlies})`;
            el.turnText.textContent = `Draw — ${reasonLabel}.`;
            return;
        }

        const isMovement = gs.phase === "MOVEMENT";
        el.phaseBadge.textContent = isMovement ? "Phase 2 · Move" : "Phase 1 · Place";
        if (isMovement) {
            el.phaseBadge.classList.add("phase-movement");
        }

        if (gs.aiThinking) {
            el.turnText.textContent = "NeuralNetwork AI is thinking...";
            return;
        }

        const myTurn = isLocalUserTurn();
        if (meta.mode === "LOCAL_PVP") {
            el.turnText.textContent = isMovement
                ? `Player ${gs.currentTurn}'s turn — Select a piece to slide`
                : `Player ${gs.currentTurn}'s turn — Tap any empty node`;
        } else if (myTurn) {
            el.turnText.textContent = isMovement
                ? `Your turn (${gs.currentTurn}) — Select a piece and tap a highlighted neighbor`
                : `Your turn (${gs.currentTurn}) — Tap any empty node to place`;
        } else {
            const waitingName = gs.currentTurn === "X" ? gs.playerX : gs.playerO;
            el.turnText.textContent = `Waiting for ${waitingName} (${gs.currentTurn})...`;
        }
    }

    function renderBoard() {
        const gs = appState.gameState;
        if (!gs) {
            return;
        }

        clearChildren(el.svgAiOverlay);
        clearChildren(el.svgLastMove);
        clearChildren(el.svgEdgesLegal);
        clearChildren(el.svgWinningLine);

        const myTurn = isLocalUserTurn();
        const winningSet = new Set(gs.winningLine || []);
        const legalTargets = new Set();
        const movableOrigins = new Set();

        if (myTurn) {
            if (gs.phase === "PLACEMENT") {
                for (const m of gs.legalMoves) {
                    if (m.from === -1) {
                        legalTargets.add(m.to);
                    }
                }
            } else {
                for (const m of gs.legalMoves) {
                    movableOrigins.add(m.from);
                    if (appState.selectedFrom !== null && m.from === appState.selectedFrom) {
                        legalTargets.add(m.to);
                    }
                }
            }
        }

        // Last movement edge indicator
        if (gs.lastMove && gs.lastMove.from >= 0) {
            el.svgLastMove.appendChild(createEdgeLine(gs.lastMove.from, gs.lastMove.to, "edge-last-move"));
        }

        // Highlight legal slide edges from currently selected piece
        if (appState.selectedFrom !== null) {
            for (const target of legalTargets) {
                el.svgEdgesLegal.appendChild(createEdgeLine(appState.selectedFrom, target, "edge-legal"));
            }
        }

        // Winning line overlay
        if (gs.winningLine && gs.winningLine.length === 3) {
            const first = gs.winningLine[0];
            const last = gs.winningLine[2];
            el.svgWinningLine.appendChild(createEdgeLine(first, last, "edge-winning"));
        }

        // Optional AI probability overlay (edges for movement actions, badges for placement actions)
        const placementProbByCell = new Map();
        if (appState.showAiProbs && gs.aiMeta && Array.isArray(gs.aiMeta.actionProbs)) {
            const probs = gs.aiMeta.actionProbs;
            for (let idx = 0; idx < probs.length; idx++) {
                const p = probs[idx];
                if (p <= 0.005) {
                    continue;
                }
                const action = ACTION_TABLE[idx];
                if (!action) {
                    continue;
                }
                if (action.from === -1) {
                    placementProbByCell.set(action.to, p);
                } else {
                    const line = createEdgeLine(action.from, action.to, "edge-ai-prob");
                    line.setAttribute("stroke-width", String(2 + p * 10));
                    line.setAttribute("stroke-opacity", String(Math.min(0.95, 0.25 + p * 0.85)));
                    el.svgAiOverlay.appendChild(line);
                }
            }
        }

        // Update the 9 interactive node buttons
        for (let i = 0; i < 9; i++) {
            const btn = nodeButtons[i];
            const piece = gs.board[i] || "";

            btn.className = "board-node";
            clearChildren(btn);

            if (piece === "X") {
                btn.classList.add("piece-x");
                btn.appendChild(document.createTextNode("X"));
            } else if (piece === "O") {
                btn.classList.add("piece-o");
                btn.appendChild(document.createTextNode("O"));
            }

            if (myTurn && gs.phase === "MOVEMENT" && movableOrigins.has(i)) {
                btn.classList.add("selectable");
            }
            if (appState.selectedFrom === i) {
                btn.classList.add("selected");
            }
            if (legalTargets.has(i)) {
                btn.classList.add("legal-target");
            }
            if (winningSet.has(i)) {
                btn.classList.add("winning");
            }
            if (gs.lastMove && gs.lastMove.to === i && gs.status === "IN_PROGRESS") {
                btn.classList.add("last-to");
            }

            if (placementProbByCell.has(i)) {
                const badge = document.createElement("span");
                badge.className = "prob-badge";
                badge.textContent = `${Math.round(placementProbByCell.get(i) * 100)}%`;
                btn.appendChild(badge);
            }

            const stateDesc = piece ? `occupied by ${piece}` : "empty";
            btn.setAttribute("aria-label", `Cell ${i}, ${stateDesc}`);
        }
    }

    function renderAiTopMoves(gs) {
        clearChildren(el.aiTopMoves);
        if (!appState.showAiProbs || !gs.aiMeta || !Array.isArray(gs.aiMeta.actionProbs)) {
            el.aiTopMoves.classList.add("hidden");
            return;
        }

        const ranked = [];
        for (let idx = 0; idx < gs.aiMeta.actionProbs.length; idx++) {
            const prob = gs.aiMeta.actionProbs[idx];
            if (prob > 0.001) {
                ranked.push({ idx, prob });
            }
        }
        ranked.sort((a, b) => b.prob - a.prob);

        if (ranked.length === 0) {
            el.aiTopMoves.classList.add("hidden");
            return;
        }

        for (const item of ranked.slice(0, 4)) {
            const action = ACTION_TABLE[item.idx];
            const label = action.from === -1
                ? `Place ${action.to}`
                : `Move ${action.from} → ${action.to}`;
            const chip = document.createElement("span");
            chip.className = "ai-move-chip";
            chip.textContent = `${label}: ${(item.prob * 100).toFixed(1)}%`;
            el.aiTopMoves.appendChild(chip);
        }
        el.aiTopMoves.classList.remove("hidden");
    }

    function createEdgeLine(from, to, className) {
        return createSvgElement("line", {
            x1: NODE_COORDS[from].x,
            y1: NODE_COORDS[from].y,
            x2: NODE_COORDS[to].x,
            y2: NODE_COORDS[to].y,
            class: className
        });
    }

    function createSvgElement(tag, attrs) {
        const node = document.createElementNS(SVG_NS, tag);
        for (const [k, v] of Object.entries(attrs)) {
            node.setAttribute(k, String(v));
        }
        return node;
    }

    function clearChildren(node) {
        while (node.firstChild) {
            node.removeChild(node.firstChild);
        }
    }

    function updateQueueUi() {
        el.queueIdlePanel.classList.toggle("hidden", appState.inQueue);
        el.queueWaitingPanel.classList.toggle("hidden", !appState.inQueue);
    }

    function resetMatchState() {
        appState.matchMeta = null;
        appState.gameState = null;
        appState.selectedFrom = null;
        appState.opponentDisconnected = false;
        appState.rematchRequestedByMe = false;
        appState.aiFailedOnTurn = false;
        el.rematchHint.classList.add("hidden");
        el.aiRetryRow.classList.add("hidden");
        el.btnPlayAgain.disabled = false;
    }

    function showView(viewName) {
        el.viewLogin.classList.toggle("active", viewName === "login");
        el.viewLobby.classList.toggle("active", viewName === "lobby");
        el.viewGame.classList.toggle("active", viewName === "game");
    }

    function setWsPill(text, styleClass) {
        el.wsPill.textContent = text;
        el.wsPill.className = `pill ${styleClass}`;
    }

    function showToast(message, variant = "info") {
        if (appState.toastTimer) {
            clearTimeout(appState.toastTimer);
        }
        el.toast.textContent = message;
        el.toast.className = `toast toast-${variant}`;
        appState.toastTimer = setTimeout(() => {
            el.toast.classList.add("hidden");
        }, 4500);
    }

    document.addEventListener("DOMContentLoaded", init);
})();
