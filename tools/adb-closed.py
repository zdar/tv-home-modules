#!/usr/bin/env python3
"""Check that network ADB on the TV is really closed.

  python tools/adb-closed.py <tv ip> [port]

Only "connection refused" proves the port is closed. A timeout or "no route to
host" can also mean a firewall or (on macOS) Local Network privacy blocked the
check itself, so it proves nothing.
"""
import errno
import socket
import sys

if len(sys.argv) < 2:
    sys.exit(__doc__)
host = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(5)
try:
    s.connect((host, port))
    print(f"OPEN: {host}:{port} accepts connections - network ADB is still on")
    sys.exit(1)
except ConnectionRefusedError:
    print(f"CLOSED: {host}:{port} refused the connection - network ADB is off")
    sys.exit(0)
except socket.timeout:
    print(f"INCONCLUSIVE: {host}:{port} timed out (TV off/asleep, or filtered)")
    sys.exit(2)
except OSError as e:
    print(f"INCONCLUSIVE: {host}:{port}: {e} (errno {e.errno}, "
          f"{errno.errorcode.get(e.errno, '?')}) - this does not prove the port is closed")
    sys.exit(2)
finally:
    s.close()
