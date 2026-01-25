"""Systemd-aware logging utilities for radiale.

When running under systemd, log messages are prefixed with priority levels
that journald understands, enabling filtering with `journalctl -p`.

Usage:
    from radiale.logging import eprint, LOG_ERR, LOG_WARNING, LOG_INFO

    eprint("Something went wrong", level=LOG_ERR)
    eprint("Connection lost, retrying", level=LOG_WARNING)
    eprint("Connected successfully")  # defaults to LOG_INFO
"""
import os
import sys

# Systemd journal priority levels (RFC 5424)
# See: https://www.freedesktop.org/software/systemd/man/sd-daemon.html
LOG_EMERG = 0    # system is unusable
LOG_ALERT = 1    # action must be taken immediately
LOG_CRIT = 2     # critical conditions
LOG_ERR = 3      # error conditions
LOG_WARNING = 4  # warning conditions
LOG_NOTICE = 5   # normal but significant condition
LOG_INFO = 6     # informational
LOG_DEBUG = 7    # debug-level messages

# Detect if running under systemd
_SYSTEMD_JOURNAL = os.environ.get("JOURNAL_STREAM") is not None


def eprint(msg, level=LOG_INFO):
    """Print to stderr, with systemd journal level prefix if running under systemd."""
    if _SYSTEMD_JOURNAL:
        line = f"<{level}>{msg}\n"
    else:
        line = f"{msg}\n"
    sys.stderr.buffer.write(line.encode("utf-8"))
    sys.stderr.buffer.flush()
