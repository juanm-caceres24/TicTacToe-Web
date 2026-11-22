# TicTacToe-Web

A standalone Java 21 web server for **Mobile Tic-Tac-Toe** (*Three Men's Morris* with radial center connections), featuring:
- **Online PvP** with real-time matchmaking over WebSockets.
- **Player vs AI** integrated with an external `NeuralNetwork` HTTP inference server (`9` inputs → `41` actions with legal move masking).
- **Local PvP** on the same screen.
- **Responsive web UI** designed for both desktop and mobile browsers across LAN, Tailscale, and port-forwarded public connections.

> **Current Status:** Stage 3 (`v0.3.0`) — Exact Retrograde Analysis solver and `9 → 41` CSV dataset generator for `NeuralNetwork`.

---

## Requirements

- **Java:** OpenJDK 21+
- **Build tool:** Apache Maven 3.9+

---

## Quick Start

```bash
# Build and verify 0 warnings (-Xlint:all -Werror)
./scripts/build.sh --clean

# Run the full JUnit 5 test suite
./scripts/test.sh

# Generate the exact 9->41 optimal training CSV for NeuralNetwork
./scripts/generate_dataset.sh
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
│   ├── build.sh                           # Compile and package with strict warnings
│   ├── test.sh                            # Run JUnit 5 test suite
│   └── generate_dataset.sh                # Generate data/tictactoe_dataset.csv via Retrograde Analysis
├── docs/
│   ├── 00-project-specification.md        # Single source of truth
│   ├── 01-git-workflow.md                 # Simplified Solo Git Flow guide
│   ├── 02-game-engine.md                  # Mobile Tic-Tac-Toe rules, topology, and 9->41 encoding
│   ├── 03-config-network-and-ai-client.md # Config schema, CLI flags, IP discovery, and AI client
│   └── 04-solver-and-dataset.md           # Exact Retrograde solver and CSV dataset format
└── src/
    ├── main/java/com/tictactoe/
    │   ├── DatasetGenMain.java            # CLI entry point for optimal dataset generation
    │   ├── engine/                        # Pure Mobile Tic-Tac-Toe engine & MoveEncoder
    │   ├── config/                        # ServerConfig JSON loader & CliArgs parser
    │   ├── net/                           # LAN, Tailscale (100.64.0.0/10), and Public IP resolver
    │   ├── ai/                            # HTTP/1.1 NeuralNetworkClient (/health, /info, /predict)
    │   └── solver/                        # Cycle-free RetrogradeSolver & DatasetExporter
    └── test/java/com/tictactoe/
        ├── engine/                        # Unit tests for topology, rules, draw limits, and masking
        ├── config/                        # Config loading and CLI override tests
        ├── net/                           # Interface classification and mock public IP lookup tests
        ├── ai/                            # Integration tests against a live ephemeral-port mock server
        └── solver/                        # Full Bellman verification and CSV/CLI tests
```

---

## Documentation

- [Project Specification](docs/00-project-specification.md)
- [Git Workflow](docs/01-git-workflow.md)
- [Game Engine & 9→41 Encoding](docs/02-game-engine.md)
- [Configuration, Network Discovery & AI Client](docs/03-config-network-and-ai-client.md)
- [Exact Retrograde Solver & Dataset Generator](docs/04-solver-and-dataset.md)
