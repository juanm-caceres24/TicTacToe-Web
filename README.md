# TicTacToe-Web

A standalone Java 21 web server for **Mobile Tic-Tac-Toe** (*Three Men's Morris* with radial center connections), featuring:
- **Online PvP** with real-time matchmaking over WebSockets.
- **Player vs AI** integrated with an external `NeuralNetwork` HTTP inference server (`9` inputs → `41` actions with legal move masking).
- **Local PvP** on the same screen.
- **Responsive web UI** designed for both desktop and mobile browsers across LAN, Tailscale, and port-forwarded public connections.

> **Current Status:** Stage 1 (`v0.1.0`) — Project setup and core Mobile Tic-Tac-Toe engine.

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
├── scripts/
│   ├── build.sh                        # Compile and package with strict warnings
│   └── test.sh                         # Run JUnit 5 test suite
├── docs/
│   ├── 00-project-specification.md     # Single source of truth
│   ├── 01-git-workflow.md              # Simplified Solo Git Flow guide
│   └── 02-game-engine.md               # Mobile Tic-Tac-Toe rules, topology, and 9->41 encoding
└── src/
    ├── main/java/com/tictactoe/
    │   └── engine/                     # Pure Mobile Tic-Tac-Toe engine & MoveEncoder (0 runtime deps)
    └── test/java/com/tictactoe/
        └── engine/                     # Unit tests for topology, rules, draw limits, and masking
```

---

## Documentation

- [Project Specification](docs/00-project-specification.md)
- [Git Workflow](docs/01-git-workflow.md)
- [Game Engine & 9→41 Encoding](docs/02-game-engine.md)
