"""Production ASGI entry point for VoiceGrowth Clinical.

Import order is intentional: clinical_app registers longitudinal/dashboard routes and
Drive ingestion registers its startup worker on the same FastAPI application.
"""

from clinical_app import app
import drive_ingest  # noqa: F401,E402

__all__ = ["app"]
