# jabuti-mcp tool catalog

One tool per configured source. Single source: bare names (`execute_sql`).
Two or more sources: names suffixed with the normalized source id
(`execute_sql_local_pg`). Read-only tools carry MCP `readOnlyHint`;
write-enabled `execute_sql` carries `destructiveHint`.

| Tool | Args | Notes |
| --- | --- | --- |
| `execute_sql` | `sql` | Multiple `;`-separated statements all run; one payload per statement. Capped results carry `"truncated": true`. |
| `search_objects` | `object_type` (schema/table/view/column/procedure/function/index), `pattern`, `schema`, `table`, `detail_level` (names/summary/full), `limit` | Always read-only. Progressive disclosure: `names` → `summary` → `full`. `procedure`/`function` return empty + `notes` (no portable JDBC routine API). |
| `explain_sql` (opt-in) | `sql` | Never executes the target. `EXPLAIN QUERY PLAN` on SQLite; engines without a supported form return `UNSUPPORTED`. |
| `health_check` (opt-in) | — | Pool state + engine-aware liveness probe (`probe.select1_ok`) + DB version. Engine-specific metrics degrade to `notes`. |
| `database_info` (opt-in) | — | Product/version, driver, redacted URL, identifier quoting, catalog/schema terms, transaction support, SQL keywords. Unavailable values degrade to `notes`. |

Opt-in tools are enabled per source in `datasources.yaml`:

```yaml
tools:
  - { name: execute_sql, source: local_pg, readonly: true, maxRows: 100 }
  - { name: search_objects, source: local_pg }
  - { name: explain_sql, source: local_pg }
  - { name: health_check, source: local_pg }
  - { name: database_info, source: local_pg }
```

Omit `tools:` entirely to default to `execute_sql` + `search_objects`.

## Result envelope

Success: `{"success": true, "data": {"statements": [...], "source_id": "..."}}`
Failure: `{"success": false, "error": "...", "code": "READONLY_VIOLATION" | ...}`
— the MCP response also carries `isError: true`, so clients render it.

## Read-only gate

`readonly: true` (the default) admits only `SELECT`/`WITH` statements,
parsed with JSqlParser. Everything else — DML/DDL/ADMIN, parse failures,
`EXPLAIN`, `SHOW`, `SET`, and PostgreSQL data-modifying CTEs
(`WITH x AS (DELETE …) SELECT …`) — is denied with `READONLY_VIOLATION`.
Statement splitting follows the target's lexer rules (MySQL/MariaDB honor
backslash escapes), so the gate and the executor see the same statements.
Set `readonly: false` on the tool entry to allow writes — confirm with the
user before doing so.

## Prompts

Registered once per deployment (not per source); optional `source` argument
disambiguates with several sources.

| Prompt | Args | Purpose |
| --- | --- | --- |
| `explore_database` | `source` (optional) | Guided schema exploration via progressive disclosure |
| `er_diagram` | `source` (optional) | Mermaid ER diagram (relationships inferred from naming/keys; foreign keys not reported yet) |
