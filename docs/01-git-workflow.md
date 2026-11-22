# Git Workflow — Simplified Solo Git Flow

This repository follows **Simplified Solo Git Flow** for clean, traceable milestone delivery without pull request overhead.

---

## 1. Branch Model

| Branch | Purpose | Rules |
| :--- | :--- | :--- |
| `main` | Stable release branch | Always compiles with **0 warnings** and passes **100% of tests**. Never commit directly. Only receives `--no-ff` merges from `development` at milestone completion, tagged with semantic versions (`v0.1.0`, `v0.2.0`, ...). |
| `development` | Active integration branch | Receives `--no-ff` merges from short-lived topic branches once verified. |
| `feat/<topic>` | New features | Branched from `development`, merged back with `--no-ff`, then deleted. |
| `fix/<topic>` | Bug fixes | Branched from `development`, merged back with `--no-ff`, then deleted. |
| `docs/<topic>` | Documentation-only changes | Branched from `development`, merged back with `--no-ff`, then deleted. |
| `perf/<topic>` | Performance improvements | Branched from `development`, merged back with `--no-ff`, then deleted. |

---

## 2. Commit Message Convention

All commits follow **Conventional Commits** in English:

```text
<type>(<scope>): <imperative summary>
```

Allowed types: `feat`, `fix`, `test`, `docs`, `build`, `perf`, `refactor`, `chore` (releases/maintenance), and `merge` (non-fast-forward branch integration commits).

Examples:
- `build(setup): initialize maven project structure, scripts, and core docs`
- `feat(engine): implement mobile tic-tac-toe rules and 9-to-41 move encoder`
- `test(engine): add unit tests for topology, movement rules, and draw limits`
- `docs(engine): document game rules and canonical 41-action index table`

---

## 3. Merge & Release Checklist

Before merging any topic branch into `development`:
1. Run `./scripts/build.sh --clean` → must compile with **0 warnings** (`-Xlint:all -Werror`).
2. Run `./scripts/test.sh` → **100% of tests** must pass.
3. Verify all files under `docs/` and `README.md` match the current codebase.
4. Merge with `--no-ff` and delete the topic branch:
   ```bash
   git checkout development
   git merge --no-ff feat/<topic>
   git branch -d feat/<topic>
   ```

At the end of each milestone:
1. Re-verify `./scripts/build.sh --clean && ./scripts/test.sh` on `development`.
2. Merge `development` into `main` with `--no-ff` and create an annotated semantic tag:
   ```bash
   git checkout main
   git merge --no-ff development -m "chore(release): merge development for v0.X.0"
   git tag -a v0.X.0 -m "Release v0.X.0: <summary>"
   git checkout development
   ```
3. Push is performed manually by the repository owner:
   ```bash
   git push origin main development --tags
   ```
