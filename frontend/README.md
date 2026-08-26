# Engineering Authoring Coach frontend

React 19 + TypeScript + Vite application with OIDC Authorization Code + PKCE.

- `/app`: projects, course overview, structured authoring, revision history, and reports.
- `/admin`: courses and materials, model profiles, read-only MCP, review operations, and audit.

All product copy is English. Student responses contain course evidence but never internal content-space identifiers or infrastructure terminology. The browser calls only `/api/v1` on `authoring-service`; Vite never proxies Content directly.

```bash
npm install
npm run lint
npm run build
npm run test
```

Set `VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`, and optionally `VITE_OIDC_AUDIENCE` before running the application.
