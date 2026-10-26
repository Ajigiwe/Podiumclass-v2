"""
Django settings for Podium LMS backend.
"""

from pathlib import Path
import logging
import os
import sys
from dotenv import load_dotenv

from django.core.exceptions import ImproperlyConfigured

logger = logging.getLogger(__name__)

BASE_DIR = Path(__file__).resolve().parent.parent

# Add apps folder to Python path
sys.path.insert(0, str(BASE_DIR))

# Load .env.local from lite-lms directory or local backend/.env
env_paths = [
    BASE_DIR.parent / 'lite-lms' / '.env.local',
    BASE_DIR / '.env',
    BASE_DIR.parent / '.env.local',
]
for ep in env_paths:
    if ep.exists():
        load_dotenv(ep)
        break


# ---------------------------------------------------------------------------
# Environment helpers
#
# Every one of these is fail-safe: an unset variable resolves to the SECURE
# choice, never to a known/guessable literal. Development opts in explicitly
# via .env.local; nothing here can silently weaken a production deployment.
# ---------------------------------------------------------------------------
def env_bool(name, default=False):
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in ('true', '1', 'yes', 'on')


def env_list(name, default=()):
    raw = os.getenv(name)
    if raw is None:
        return list(default)
    return [item.strip() for item in raw.split(',') if item.strip()]


def env_secret(name, dev_placeholder):
    """
    Read a service credential (LiveKit, attendance API).

    Never fatal: an unset value yields an unusable, per-boot placeholder, so the
    dependent feature fails closed at request time instead of preventing the
    whole process from starting. That matters because these features can be
    switched off (``classroom_live``, ``external_attendance_api``) -- a
    deployment that disables them must still boot without their secrets.

    There is deliberately NO hardcoded fallback: a literal in source control is
    not a secret.
    """
    value = os.getenv(name)
    if value:
        return value
    logger.warning(
        "%s is not set. The service it authorises will reject this deployment's "
        "requests (failing closed).", name,
    )
    return f"{dev_placeholder}-{os.urandom(4).hex()}"


# Fail-safe default: an unset DJANGO_DEBUG means PRODUCTION. Local development
# opts in through .env.local. The previous default was 'True', so a forgotten
# variable shipped DEBUG mode to production -- including the 404 page that
# prints the entire URLconf.
DEBUG = env_bool('DJANGO_DEBUG', env_bool('DEBUG', False))

# Credential-less sign-in for local/mobile testing (the "DEV / MOBILE QUICK
# ACCESS" buttons). Off unless DJANGO_DEV_LOGIN is set explicitly.
#
# This is only the switch. The *effective* test is `DEV_LOGIN_ENABLED and
# DEBUG`, applied at every point of use, so a deployment with DEBUG off
# (production) can never enable this however else it is configured -- and both
# halves stay independently testable.
DEV_LOGIN_ENABLED = env_bool('DJANGO_DEV_LOGIN', False)
if DEV_LOGIN_ENABLED:
    if DEBUG:
        logger.warning(
            "DJANGO_DEV_LOGIN is on: POST /api/v1/accounts/google/ will mint "
            "JWTs without a Google credential, including an admin one. Unset "
            "it wherever that URL is reachable by anyone but you.",
        )
    else:
        logger.warning(
            "DJANGO_DEV_LOGIN is set but DJANGO_DEBUG is off, so "
            "credential-less sign-in stays disabled. Unset DJANGO_DEV_LOGIN "
            "in this environment to silence this.",
        )

# SECRET_KEY signs session cookies, password-reset tokens AND (by inheritance)
# PODIUM_JWT_SECRET, i.e. every authentication token in the system.
SECRET_KEY = os.getenv('DJANGO_SECRET_KEY') or os.getenv('NEXTAUTH_SECRET')
if not SECRET_KEY:
    if DEBUG:
        SECRET_KEY = 'django-insecure-DEV-ONLY-never-reaches-production'
        logger.warning(
            "DJANGO_SECRET_KEY is not set. Using a development-only key. "
            "Generate one for any shared environment: "
            "python -c \"from django.core.management.utils import get_random_secret_key as g; print(g())\"",
        )
    else:
        raise ImproperlyConfigured(
            "DJANGO_SECRET_KEY must be set when DJANGO_DEBUG is off. "
            "It signs every authentication token; a guessable value allows "
            "anyone to forge an admin JWT."
        )

# Never '*'. Django validates the Host header against this list; a wildcard
# accepts any value and enables Host-header attacks (cache poisoning,
# password-reset link injection).
ALLOWED_HOSTS = env_list('ALLOWED_HOSTS')
if not ALLOWED_HOSTS:
    if DEBUG:
        ALLOWED_HOSTS = ['*']
    else:
        raise ImproperlyConfigured(
            "ALLOWED_HOSTS must list the public hostname(s) when DJANGO_DEBUG is off, "
            "e.g. ALLOWED_HOSTS=api.example.com"
        )
elif DEBUG and '*' not in ALLOWED_HOSTS:
    ALLOWED_HOSTS = list(set(ALLOWED_HOSTS + ['*']))

# Application definition
INSTALLED_APPS = [
    'django.contrib.admin',
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',

    # Third-party
    'rest_framework',
    'corsheaders',

    # Podium domain apps
    'apps.core',
    'apps.accounts',
    'apps.classrooms',
    'apps.attendance',
    'apps.billing',
    'apps.storage',
]

MIDDLEWARE = [
    'corsheaders.middleware.CorsMiddleware',
    'django.middleware.security.SecurityMiddleware',
    'django.contrib.sessions.middleware.SessionMiddleware',
    'django.middleware.common.CommonMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
]

ROOT_URLCONF = 'config.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.debug',
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
            ],
        },
    },
]

WSGI_APPLICATION = 'config.wsgi.application'

# Database Configuration (SQLite locally for dev, PostgreSQL in Docker/Linux production)
USE_POSTGRES = os.getenv('USE_POSTGRES', 'False').lower() in ('true', '1')
DATABASE_URL = os.getenv('DATABASE_URL')

if USE_POSTGRES and DATABASE_URL and DATABASE_URL.startswith('postgresql'):
    # Parse standard connection string: postgresql://user:password@host:port/dbname
    import urllib.parse as urlparse
    url = urlparse.urlparse(DATABASE_URL)
    DATABASES = {
        'default': {
            'ENGINE': 'django.db.backends.postgresql',
            'NAME': url.path[1:],
            'USER': url.username,
            'PASSWORD': url.password,
            'HOST': url.hostname,
            'PORT': url.port or 5432,
        }
    }
else:
    DATABASES = {
        'default': {
            'ENGINE': 'django.db.backends.sqlite3',
            'NAME': BASE_DIR / 'db.sqlite3',
        }
    }

AUTH_USER_MODEL = 'accounts.User'

AUTH_PASSWORD_VALIDATORS = [
    {'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator'},
    {'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator'},
    {'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator'},
    {'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator'},
]

LANGUAGE_CODE = 'en-us'
TIME_ZONE = 'UTC'
USE_I18N = True
USE_TZ = True

STATIC_URL = 'static/'
STATIC_ROOT = BASE_DIR / 'staticfiles'

DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'

# REST Framework Configuration (Enforcing the Document Object contract)
REST_FRAMEWORK = {
    'DEFAULT_RENDERER_CLASSES': [
        'apps.core.renderers.DocumentJSONRenderer',
    ],
    'DEFAULT_PAGINATION_CLASS': 'apps.core.pagination.DocumentPagination',
    'PAGE_SIZE': 25,
    'DEFAULT_AUTHENTICATION_CLASSES': [
        'apps.accounts.auth.PodiumAuthentication',
        'rest_framework.authentication.SessionAuthentication',
    ],
    # DRF otherwise treats ?format= as a renderer selector. The external
    # attendance contract uses ?format=csv|json as an ordinary parameter, so a
    # request like ?format=xml would 404 during content negotiation before the
    # view could reject it. No endpoint here selects a renderer by query string.
    'URL_FORMAT_OVERRIDE': None,
}

# JWT access tokens (PyJWT, HS256). Inherit from SECRET_KEY unless overridden,
# so rotating SECRET_KEY invalidates outstanding tokens.
PODIUM_JWT_SECRET = os.getenv('PODIUM_JWT_SECRET')
PODIUM_JWT_TTL_DAYS = int(os.getenv('PODIUM_JWT_TTL_DAYS', '7'))
PODIUM_JWT_ISSUER = os.getenv('PODIUM_JWT_ISSUER', 'podium-lms')

# Trusted browser origins for CORS.
#
# Explicit origins win. Allow-all is a DEVELOPMENT convenience only: with
# CORS_ALLOW_CREDENTIALS on, a wildcard would let any website drive the API on
# a signed-in user's behalf. The previous code hardcoded
# CORS_ALLOW_ALL_ORIGINS = True two lines below an env-driven assignment,
# silently ignoring CORS_ALLOWED_ORIGINS in every environment including
# production.
CORS_ALLOWED_ORIGINS = env_list('CORS_ALLOWED_ORIGINS')
CORS_ALLOW_ALL_ORIGINS = not CORS_ALLOWED_ORIGINS and DEBUG
# Credentials only make sense with an explicit origin allowlist. Authentication
# here is Bearer-token based, so the browser does not need cookies at all.
CORS_ALLOW_CREDENTIALS = bool(CORS_ALLOWED_ORIGINS)

if not CORS_ALLOWED_ORIGINS and not DEBUG:
    logger.warning(
        "CORS_ALLOWED_ORIGINS is not set and DJANGO_DEBUG is off; no browser "
        "origin will be allowed to call this API. Set it to the deployed "
        "frontend origin(s), comma-separated."
    )

# ---------------------------------------------------------------------------
# HTTPS / transport hardening (only meaningful off DEBUG)
# ---------------------------------------------------------------------------
if not DEBUG:
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    SECURE_CONTENT_TYPE_NOSNIFF = True
    SECURE_REFERRER_POLICY = 'same-origin'

# Both are opt-in: HSTS is effectively irreversible (browsers cache it), and an
# SSL redirect is wrong when a load balancer already terminates TLS.
SECURE_SSL_REDIRECT = env_bool('DJANGO_SECURE_SSL_REDIRECT', False)
SECURE_HSTS_SECONDS = int(os.getenv('DJANGO_SECURE_HSTS_SECONDS', '0'))
SECURE_HSTS_INCLUDE_SUBDOMAINS = env_bool('DJANGO_SECURE_HSTS_INCLUDE_SUBDOMAINS', False)

# Set only behind a proxy that overwrites X-Forwarded-Proto; otherwise a client
# could spoof it to defeat SECURE_SSL_REDIRECT.
if env_bool('DJANGO_BEHIND_PROXY', False):
    SECURE_PROXY_SSL_HEADER = ('HTTP_X_FORWARDED_PROTO', 'https')

# External APIs
# The service-to-service key is never hardcoded: a literal in the repository is
# public, and this key authorises roster reads and attendance writes.
ATTENDANCE_API_KEY = env_secret('ATTENDANCE_API_KEY', 'dev-only-attendance-key')
ATTENDANCE_API_RATE_LIMIT = int(os.getenv('ATTENDANCE_API_RATE_LIMIT', '120'))

LIVEKIT_API_URL = os.getenv('LIVEKIT_API_URL', 'http://localhost:7880')
# Signing material for room-join tokens. A known secret lets anyone mint a token
# for any room. Set LIVEKIT_API_KEY / LIVEKIT_API_SECRET in the environment.
LIVEKIT_API_KEY = env_secret('LIVEKIT_API_KEY', 'dev-only-livekit-key')
LIVEKIT_API_SECRET = env_secret('LIVEKIT_API_SECRET', 'dev-only-livekit-secret')
# WebSocket URL clients use to join rooms (defaults to self-hosted local server)
LIVEKIT_URL = os.getenv('LIVEKIT_URL', 'ws://localhost:7880')

# LiveKit Egress: server-side recording. The worker is its own container
# (scripts/start_egress.ps1) and nothing in this project records from a
# browser. Calls go to the LiveKit *server*, which exposes the
# `/twirp/livekit.Egress/*` API and forwards each request to a worker over
# Redis -- there is no separate egress API port to reach (its own HTTP ports
# are health/template/metrics only). When no worker is running, or Redis is
# down, every recording call fails closed with 503 instead of pretending the
# lecture was captured; set this to an empty string to switch recording off at
# the transport level as well as the feature flag.
LIVEKIT_EGRESS_URL = os.getenv('LIVEKIT_EGRESS_URL', LIVEKIT_API_URL)

# Where MP4s land on this machine. The egress container mounts the same
# directory at RECORDINGS_CONTAINER_PATH, so a path handed to the worker and a
# path read back here refer to one file. Recording rows store only the
# basename, which keeps the two sides from drifting apart (and stops a stored
# path from ever pointing outside the root).
RECORDINGS_ROOT = Path(
    os.getenv('RECORDINGS_ROOT', str(BASE_DIR.parent / '.livekit' / 'recordings'))
).resolve()
RECORDINGS_CONTAINER_PATH = os.getenv('RECORDINGS_CONTAINER_PATH', '/out/recordings')
