#!/usr/bin/env python
"""Entry point wrapper: `python harness/harness.py <command>`."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from harness_lib.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
