# CLAUDE.md

> This file is auto-loaded by Claude Code at session start. It deliberately holds **no
> project content** — two files claiming to be the source of truth is how documentation
> rots.

## Read these first, in this order

1. **[`Agent.md`](Agent.md)** — the complete project context: what this project is, the
   stack, service/port map, current implementation status, locked decisions with their
   rationale, and the traps that will cost you time. **Start here.**
2. **[`plan.md`](plan.md)** — the canonical phased plan with exit criteria. Find the current
   phase and its open checkboxes before proposing any work.
3. **[`docs/INTERVIEW-GUIDE.md`](docs/INTERVIEW-GUIDE.md)** — how the project is explained in
   interviews. **Update it with every phase**, and never let it claim more than is built.

Original design conversation: `../Order & Inventory Platform.pdf` (outside the repo,
257 pages, text-extractable with `pdftotext -layout`).

## Rules for working in this repo

- **Update `Agent.md` and `docs/INTERVIEW-GUIDE.md` in the same commit as the change.** Tick
  the `plan.md` checkbox, add a dated entry to `Agent.md` §10 Change log, refresh §5
  Implementation status, and keep the interview guide from claiming more than is built. The
  full protocol is `Agent.md` §0.
- **Do not start a phase before the previous phase's exit criteria pass.** Each phase must
  leave a running system.
- **This is a portfolio project.** Optimise for defensible interview talking points, not
  feature count. Never claim a capability that is not implemented and tested.
- `config-repo` is a **git submodule** on branch `master`, and Config Server reads only
  **committed** state. Changing configuration is a two-commit operation — see `Agent.md` §6.
- `java` on PATH is Java 8 and `mvn` is not installed. Use JDK 21 and each module's `./mvnw`.
