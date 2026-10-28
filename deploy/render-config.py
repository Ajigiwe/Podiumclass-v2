#!/usr/bin/env python3
"""
Render deploy/*.yaml.template into deploy/generated/*.yaml.

Why render at all: LiveKit and Egress both need the API key/secret in a config
file, and a file committed to the repository is not a secret. The templates stay
in git; the rendered copies are gitignored and produced on the server from
deploy/.env at deploy time.

Every ${NAME} placeholder must be present in the environment -- a silent empty
substitution would produce a LiveKit server with no keys that rejects every join
with an unhelpful "invalid token".
"""

import os
import re
import sys
from pathlib import Path

DEPLOY_DIR = Path(__file__).resolve().parent
OUT_DIR = DEPLOY_DIR / "generated"

PLACEHOLDER = re.compile(r"\$\{([A-Z0-9_]+)\}")

TEMPLATES = ["livekit.yaml.template", "egress.yaml.template"]


def render(template_path: Path, out_path: Path) -> None:
    text = template_path.read_text(encoding="utf-8")
    missing = sorted({m for m in PLACEHOLDER.findall(text) if not os.environ.get(m)})
    if missing:
        sys.exit(
            f"Cannot render {template_path.name}: set {', '.join(missing)} in deploy/.env"
        )
    rendered = PLACEHOLDER.sub(lambda m: os.environ[m.group(1)], text)
    out_path.write_text(rendered, encoding="utf-8", newline="\n")
    # These contain the LiveKit secret.
    out_path.chmod(0o600)
    print(f"  {out_path.relative_to(DEPLOY_DIR.parent)}")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.chmod(0o700)
    print("Rendering service configs:")
    for name in TEMPLATES:
        src = DEPLOY_DIR / name
        if not src.exists():
            sys.exit(f"Missing template: {src}")
        render(src, OUT_DIR / name.replace(".template", ""))


if __name__ == "__main__":
    main()
