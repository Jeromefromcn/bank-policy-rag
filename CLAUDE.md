# CLAUDE.md

RAG service over banking supervisory guidance (HKMA SPM) and fictional Example Bank policies.
`ROADMAP.md` is the master plan — read §0–§4 and §10 before working on anything.

## Rules
- **English only.** All repo content — code, comments, docs, commits, test data — is English.
- **Diagrams use Mermaid.** No ASCII art or image files for diagrams.
- **Spec first.** Each Phase starts with a spec and plan in `docs/specs/`; write code only after the author approves.
- TDD. One logical change per commit, English imperative commit messages.
- **Never commit SPM text** — no PDFs, no extracted or chunked text, no HKMA URLs. Test fixtures use self-made text.
- `decisions/`, golden set answers and Example Bank documents are author-owned: draft only, never commit AI-generated content directly.
- Eval numbers come only from real runner output. Report regressions as they are.
- Every container (compose and Testcontainers) has CPU and memory limits.
- No secrets in git; pin all dependency versions and image tags.
- Retrieval permission filtering is a pre-filter in ES, never a post-filter.

## Progress
Tick boxes in `ROADMAP.md` (Progress section and §5) in the same commit that completes the work.
