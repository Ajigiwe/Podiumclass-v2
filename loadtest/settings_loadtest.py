"""
Load-test settings: the shipped settings with persistent database connections.

`config.settings` leaves CONN_MAX_AGE at Django's default of 0, so every request
opens, authenticates and closes a Postgres connection. On this machine that
costs ~250ms per connection through Docker's NAT, which alone caps the whole API
(~16 threads / 0.25s = 64 rps) no matter how much work the endpoint does.

Pointing a server at this module answers the other question: how much can the
application itself serve once connection churn is not the bottleneck?

    PYTHONPATH=loadtest DJANGO_SETTINGS_MODULE=settings_loadtest \
      python -m waitress --listen=127.0.0.1:8002 --threads=16 --connection-limit=1000 \
      config.wsgi:application
"""

from config.settings import *  # noqa: F401,F403

DATABASES["default"]["CONN_MAX_AGE"] = 60  # noqa: F405
DATABASES["default"]["CONN_HEALTH_CHECKS"] = True  # noqa: F405
