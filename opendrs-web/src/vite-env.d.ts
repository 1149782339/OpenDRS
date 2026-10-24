/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Optional absolute API origin, e.g. `http://localhost:8080`.
   *
   * Left unset (the default) the client emits root-relative paths such as
   * `/api/v1/datasources`, which is what the nginx reverse proxy deployment needs.
   * Set it at build time only when the SPA is served from a different origin than the
   * API, in which case the backend must also allow that origin via
   * `OPENDRS_CORS_ALLOWED_ORIGINS`.
   */
  readonly VITE_API_BASE_URL?: string
}
