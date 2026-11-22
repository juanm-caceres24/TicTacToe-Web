# TicTacToe-Web

A standalone Java 21 web server for **Mobile Tic-Tac-Toe** (*Three Men's Morris* with radial center connections), featuring:
- **Online PvP** with real-time matchmaking over WebSockets.
- **Player vs AI** integrated with an external `NeuralNetwork` HTTP inference server (`9` inputs → `41` actions with legal move masking).
- **Local PvP** on the same screen.
- **Responsive web UI** designed for both desktop and mobile browsers across LAN, Tailscale, and port-forwarded public connections.

> **Current Status:** `v0.5.1` — Complete, audit-hardened release with responsive desktop/mobile frontend, SVG two-phase board, online matchmaking, local PvP, and `NeuralNetwork` AI integration.

---

## Requirements

- **Java:** OpenJDK 21+
- **Build tool:** Apache Maven 3.9+

---

## Quick Start

```bash
# 1. Build shaded executable JAR and verify 0 warnings (-Xlint:all -Werror)
./scripts/build.sh --clean

# 2. Run the full JUnit 5 unit & WebSocket E2E test suite
./scripts/test.sh

# 3. (Optional) Generate the 9->41 optimal training CSV for your NeuralNetwork project
./scripts/generate_dataset.sh

# 4. Launch the standalone server (prints Local LAN, Tailscale, and Public URLs + AI status)
./scripts/run.sh

# 5. Run live HTTP & static-asset smoke tests against the shaded JAR
./scripts/test_api.sh
```

Override key `configs/server.json` options at launch time (`--config`, `--host`, `--port`, `--ai-host`, `--ai-port`, `--no-public-ip`):

```bash
./scripts/run.sh --port 3000 --ai-host 127.0.0.1 --ai-port 8080
./scripts/run.sh --help
```

---

## Repository Structure

```text
TicTacToe-Web/
├── pom.xml
├── configs/
│   ├── server.json                        # External server, IP lookup, and AI server config
│   └── tictactoe_nn_config.json           # Reference 9->41 architecture config for NeuralNetwork
├── scripts/
│   ├── build.sh                           # Compile and package shaded JAR with strict warnings
│   ├── test.sh                            # Run JUnit 5 test suite
│   ├── run.sh                             # Launch standalone shaded JAR with CLI flags
│   ├── test_api.sh                        # Live HTTP & static-asset smoke test with curl
│   └── generate_dataset.sh                # Generate data/tictactoe_dataset.csv via Retrograde Analysis
├── docs/
│   ├── 00-project-specification.md        # Single source of truth
│   ├── 01-git-workflow.md                 # Simplified Solo Git Flow guide
│   ├── 02-game-engine.md                  # Mobile Tic-Tac-Toe rules, topology, and 9->41 encoding
│   ├── 03-config-network-and-ai-client.md # Config schema, CLI flags, IP discovery, and AI client
│   ├── 04-solver-and-dataset.md           # Exact Retrograde solver and CSV dataset format
│   ├── 05-server-and-protocol.md          # Javalin server, matchmaking, REST & WebSocket protocol
│   └── 06-web-frontend.md                 # Responsive vanilla UI, SVG board & AI overlay
└── src/
    ├── main/
    │   ├── java/com/tictactoe/
    │   │   ├── Main.java                  # Server entry point (--help, CLI overrides, IP banner)
    │   │   ├── DatasetGenMain.java        # CLI entry point for optimal dataset generation
    │   │   ├── engine/                    # Pure Mobile Tic-Tac-Toe engine & MoveEncoder
    │   │   ├── config/                    # ServerConfig JSON loader, CliArgs parser & BuildInfo
    │   │   ├── net/                       # LAN, Tailscale (100.64.0.0/10), and Public IP resolver
    │   │   ├── ai/                        # HTTP/1.1 NeuralNetworkClient (/health, /info, /predict)
    │   │   ├── solver/                    # Cycle-free RetrogradeSolver & DatasetExporter
    │   │   ├── match/                     # MatchmakingQueue, GameMatch, concurrent MatchManager
    │   │   └── web/                       # Javalin WebServer, RestController, GameWebSocketHandler
    │   └── resources/web/                 # Responsive zero-framework UI (index.html, styles.css, app.js)
    └── test/java/com/tictactoe/
        ├── engine/                        # Topology, rules, draw limits, and masking tests
        ├── config/                        # Config loading and CLI override tests
        ├── net/                           # Interface classification and mock public IP tests
        ├── ai/                            # Mock NeuralNetwork HTTP integration tests
        ├── solver/                        # Full Bellman verification and CSV/CLI tests
        ├── match/                         # Matchmaking, rematch, and disconnect tests
        └── web/                           # Full E2E Javalin REST, static asset & WebSocket tests
```

---

## Documentation

- [Project Specification](docs/00-project-specification.md)
- [Git Workflow](docs/01-git-workflow.md)
- [Game Engine & 9→41 Encoding](docs/02-game-engine.md)
- [Configuration, Network Discovery & AI Client](docs/03-config-network-and-ai-client.md)
- [Exact Retrograde Solver & Dataset Generator](docs/04-solver-and-dataset.md)
- [Web Server, Matchmaking & Protocol](docs/05-server-and-protocol.md)
- [Responsive Web Frontend](docs/06-web-frontend.md)
