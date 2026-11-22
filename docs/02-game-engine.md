# Game Engine — Mobile Tic-Tac-Toe (`com.tictactoe.engine`)

This document describes the pure, framework-free game engine implemented in Stage 1 (`v0.1.0`). It has zero runtime dependencies outside the Java 21 standard library.

---

## 1. Board Indexing & Movement Topology

Cells are indexed in row-major order (`0..8`):

```text
0 — 1 — 2
| \ | / |
3 — 4 — 5
| / | \ |
6 — 7 — 8
```

### Adjacency Rules (`BoardTopology`)
- **Perimeter ring:** `0 ↔ 1 ↔ 2 ↔ 5 ↔ 8 ↔ 7 ↔ 6 ↔ 3 ↔ 0` (each perimeter cell connects to its 2 contiguous perimeter neighbors).
- **Radial center connections:** Center cell `4` connects bidirectionally to all 8 perimeter cells (`0, 1, 2, 3, 5, 6, 7, 8`).
- **Disallowed jumps:** Non-contiguous perimeter jumps (e.g., `0 → 2`, `1 → 3`, `3 → 5`) are illegal.
- **No stalemate property:** Exhaustive verification over all $\binom{9}{3}\binom{6}{3} = 1{,}680$ possible 3-vs-3 piece layouts confirms that **every non-terminal state has at least one legal movement** (any arrangement that blocks all 3 pieces of a player is already a winning 3-in-a-row for one of the two players).

---

## 2. Game Lifecycle & Draw Conditions (`GameEngine`)

1. **Placement Phase (`GamePhase.PLACEMENT`):**
   - Active while `getPieceCount(currentTurn) < 3`.
   - Note on ply 6: after `X` places its 3rd piece on ply 5 (without winning), `O` still has 2 pieces, so ply 6 remains in `PLACEMENT` for `O`. `MOVEMENT` begins on ply 7 once both players have 3 pieces on the board.
2. **Movement Phase (`GamePhase.MOVEMENT`):**
   - Active once `getPieceCount(currentTurn) == 3`.
   - The current player slides one owned piece from `from` to an adjacent empty cell `to`.
3. **Victory Detection:**
   - Checked immediately after every move across the 8 winning lines (3 rows, 3 columns, 2 diagonals).
   - Victory always takes precedence over draw checks (winning on the final allowed movement ply awards a win, not a draw).
4. **Cycle & Infinite-Game Prevention:**
   - **Position repetition (`DrawReason.THREEFOLD_REPETITION`):** Upon entering the full 3-vs-3 movement phase, each `(board, currentTurn)` state key is counted (the initial 3-vs-3 state starts at occurrence `1`). Reaching `repetitionLimit` (default `3`) ends the game in a draw.
   - **Movement ply limit (`DrawReason.MOVE_LIMIT_REACHED`):** Reaching `maxMovementPlies` (default `50`) without a winner ends the game in a draw.

---

## 3. Neural Network Encoding (`9 → 41`, `MoveEncoder`)

### Input Vector (`input_size = 9`)
9 `double` values in row-major order (`0..8`), **player-relative** to `currentTurn`:
- `+1.0` = current player's piece
- `-1.0` = opponent's piece
- `0.0` = empty cell

Because the current phase is uniquely determined by whether the count of `+1.0` entries is `< 3` (`PLACEMENT`) or `== 3` (`MOVEMENT`), no extra phase neuron is required.

### Canonical Action Index Table (`output_size = 41`)

| Action Index | Move Type | Semantics |
| :--- | :--- | :--- |
| `0..8` | Placement | `PLACE(i)` onto cell `i` (`0..8`) |
| `9, 10, 11` | Movement (`from = 0`) | `0→1`, `0→3`, `0→4` |
| `12, 13, 14` | Movement (`from = 1`) | `1→0`, `1→2`, `1→4` |
| `15, 16, 17` | Movement (`from = 2`) | `2→1`, `2→4`, `2→5` |
| `18, 19, 20` | Movement (`from = 3`) | `3→0`, `3→4`, `3→6` |
| `21..28` | Movement (`from = 4`) | `4→0`, `4→1`, `4→2`, `4→3`, `4→5`, `4→6`, `4→7`, `4→8` |
| `29, 30, 31` | Movement (`from = 5`) | `5→2`, `5→4`, `5→8` |
| `32, 33, 34` | Movement (`from = 6`) | `6→3`, `6→4`, `6→7` |
| `35, 36, 37` | Movement (`from = 7`) | `7→4`, `7→6`, `7→8` |
| `38, 39, 40` | Movement (`from = 8`) | `8→4`, `8→5`, `8→7` |

### Legal Move Masking (`MoveEncoder.selectBestLegalMove`)
- Masks out all illegal indices before selecting `argmax`.
- Handles both non-negative `Softmax` outputs (renormalized over legal moves) and raw negative logits (numerically stable masked softmax).
- Resilient against `NaN` or infinite scores: never selects an illegal move and deterministically falls back to the first legal move if all legal scores are `NaN`.
