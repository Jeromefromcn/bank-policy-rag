#!/usr/bin/env bash
# Fails if a tracked file contains an HKMA URL or if a PDF is tracked (ROADMAP §1.1 rules 1-3).
set -euo pipefail
cd "$(dirname "$0")/.."

pdfs="$(git ls-files '*.pdf')"
if [[ -n "$pdfs" ]]; then
  echo "tracked PDF files are not allowed:" >&2
  echo "$pdfs" >&2
  exit 1
fi

# -I skips binary files; the pattern requires a URL or the .gov.hk hostname, so prose mentions of
# "hkma.gov" in test instructions do not match.
matches="$(git ls-files -z | xargs -0 grep -IEn 'https?://[^ ]*hkma|hkma\.gov\.hk' || true)"
if [[ -n "$matches" ]]; then
  echo "HKMA URL found in tracked files:" >&2
  echo "$matches" >&2
  exit 1
fi

echo "ok"
