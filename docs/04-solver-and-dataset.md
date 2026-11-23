# Exact Retrograde Solver & Training Dataset Generator (Stage 3 — `v0.3.0`)

This document describes the cycle-free tablebase solver (`com.tictactoe.solver`) and CLI dataset generator (`DatasetGenMain`) built to train the external `NeuralNetwork` project for Mobile Tic-Tac-Toe (`9 → 41`).

---

## 1. Why Retrograde Analysis Instead of Naive Minimax

During Phase 2 (`MOVEMENT`), pieces slide indefinitely along the board graph and can repeat positions in cycles. Naive recursive Minimax loops infinitely on cyclic graphs unless truncated with heuristic depth limits.

Because Mobile Tic-Tac-Toe has a compact player-relative state space (all reachable states encoded in ternary base-3 inside `0..19,682`: exactly **`3,870` reachable states**, of which **`3,602`** are non-terminal — `2,230` Placement + `1,372` Movement — and `268` are terminal), **Retrograde Analysis** solves the entire game **100% exactly** in under 10 milliseconds:

- **Exact game-theoretic result:** First player has a forced **`WIN` in 9 plies (`DTM = 9`)**, uniquely starting with **`PLACE(4)`** (the center cell).
- **Exact non-terminal outcome distribution:** `2,161 WIN` / `936 DRAW` / `505 LOSS`.

1. **Forward BFS from the empty board (`stateId = 0`):** Discovers every reachable player-relative state (where digit `1` is the player to move, digit `2` is the opponent, and digit `0` is empty) and builds both forward and flat reverse adjacency lists.
2. **Terminal seeding:** Any reachable state where the opponent already has 3-in-a-row is marked `LOSS` with Distance-To-Mate `DTM = 0` and enqueued.
3. **Backward BFS propagation (non-decreasing DTM):**
   - Any predecessor of a `LOSS(d)` state immediately becomes `WIN(d + 1)` (fastest forced win).
   - Any predecessor whose **every** legal move leads to an opponent `WIN` becomes `LOSS(d + 1)` (longest resistance before mate).
4. **Remaining states:** Every unresolved reachable state after the backward queue empties is provably a theoretical **`DRAW`**.

---

## 2. Optimal Policy Extraction

For each reachable non-terminal state `s`:
- **`WIN`:** Optimal moves are those leading to opponent `LOSS` with the **minimum** `DTM` (e.g., immediate mate-in-1 always wins over mate-in-3).
- **`DRAW`:** Optimal moves are those maintaining `DRAW` (never allowing an opponent forced win; automatically blocks immediate threats).
- **`LOSS`:** Optimal moves are those leading to opponent `WIN` with the **maximum** `DTM` (maximizing survival plies and blocking immediate mate-in-1 whenever possible).

Because forward BFS enumerates all reachable states across the full board, all 8 rotational/reflectional symmetries of the square are inherently included with exact labels.

---

## 3. Generating the CSV Dataset (`data/tictactoe_dataset.csv`)

Run the generator script:

```bash
# Default: 9 inputs + 41 soft-target outputs, no header, shuffled with seed 42
./scripts/generate_dataset.sh

# Show all CLI flags
./scripts/generate_dataset.sh --help

# Strict one-hot targets with header row
./scripts/generate_dataset.sh --target-mode one-hot --header --output data/tictactoe_onehot.csv
```

### CSV Layout (50 Columns per Row)
- **Columns `0..8` (`9` inputs):** Player-relative board (`1` = current player, `-1` = opponent, `0` = empty).
- **Columns `9..49` (`41` targets):** Target distribution over canonical action indices `0..40` (see `docs/02-game-engine.md`), summing to `1.0`, with non-zero weight strictly on legal optimal moves:
  - `soft` (default): `1.0 / k` across all `k` tied optimal moves (ideal for `Softmax` + `CategoricalCrossEntropy`).
  - `one-hot`: `1.0` on the first canonical optimal move, `0` elsewhere.

---

## 4. Training Config Template (`configs/tictactoe_nn_config.json`)

`configs/tictactoe_nn_config.json` strictly conforms to the **`NeuralNetwork` `v0.6.0`** external configuration schema (which rejects unknown JSON keys across `model`, `model.layers[]`, `training`, and `server`):

- **Architecture (`model.layers`):** `9 → 128 (relu) → 128 (relu) → 41 (softmax)` (`3` dense layers, `23,081` parameters), saving to `models/tictactoe_model.json`.
- **Training (`training`):** `dataset_path: "data/tictactoe_dataset.csv"`, `dataset_has_header: false`, fused `loss: "cce"`, `optimizer: "adam"` (`learning_rate: 0.001`, `batch_size: 32`, `epochs: 150`), `validation_split: 0.1`, `keep_best_validation_model: true`, `num_threads: 4`.
- **Inference server (`server`):** `host: "127.0.0.1"`, `port: 8080`, `num_workers: 4` — matching `configs/server.json`'s default `aiServer` target.

Copy `data/tictactoe_dataset.csv` into your `NeuralNetwork/data/` folder (and use this template directly or `NeuralNetwork/configs/tictactoe.json`), then train and launch inference from the `NeuralNetwork` repository:

```bash
./build/release/apps/train_app configs/tictactoe.json --compact
./build/release/apps/server_app configs/tictactoe.json
```
