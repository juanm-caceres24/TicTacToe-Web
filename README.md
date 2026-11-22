# TicTacToe-Web

A standalone Java 21 web server for **Mobile Tic-Tac-Toe** (*Three Men's Morris* with radial center connections), featuring:
- **Online PvP** with real-time matchmaking over WebSockets.
- **Player vs AI** integrated with an external `NeuralNetwork` HTTP inference server (`9` inputs → `41` actions with legal move masking).
- **Local PvP** on the same screen.
- **Responsive web UI** designed for both desktop and mobile browsers across LAN, Tailscale, and port-forwarded public connections.

> **Current Status:** Stage 2 (`v0.2.0`) — External configuration, LAN/Tailscale/Public IP discovery, and `NeuralNetwork` HTTP/1.1 client.

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
```

---

## Repository Structure

```text
TicTacToe-Web/
├── pom.xml
├── configs/
│   └── server.json                        # External server, IP lookup, and AI server config
├── scripts/
│   ├── build.sh                           # Compile and package with strict warnings
│   └── test.sh                            # Run JUnit 5 test suite
├── docs/
│   ├── 00-project-specification.md        # Single source of truth
│   ├── 01-git-workflow.md                 # Simplified Solo Git Flow guide
│   ├── 02-game-engine.md                  # Mobile Tic-Tac-Toe rules, topology, and 9->41 encoding
│   └── 03-config-network-and-ai-client.md # Config schema, CLI flags, IP discovery, and AI client
└── src/
    ├── main/java/com/tictactoe/
    │   ├── engine/                        # Pure Mobile Tic-Tac-Toe engine & MoveEncoder
    │   ├── config/                        # ServerConfig JSON loader & CliArgs parser
    │   ├── net/                           # LAN, Tailscale (100.64.0.0/10), and Public IP resolver
    │   └── ai/                            # HTTP/1.1 NeuralNetworkClient (/health, /info, /predict)
    └── test/java/com/tictactoe/
        ├── engine/                        # Unit tests for topology, rules, draw limits, and masking
        ├── config/                        # Config loading and CLI override tests
        ├── net/                           # Interface classification and mock public IP lookup tests
        └── ai/                            # Integration tests against a live ephemeral-port mock server
```

---

## Documentation

- [Project Specification](docs/00-project-specification.md)
- [Git Workflow](docs/01-git-workflow.md)
- [Game Engine & 9→41 Encoding](docs/02-game-engine.md)
- [Configuration, Network Discovery & AI Client](docs/03-config-network-and-ai-client.md)
