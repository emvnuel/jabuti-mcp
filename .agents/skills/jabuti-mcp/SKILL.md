---
name: jabuti-mcp
description: Query and explore relational databases through the jabuti-mcp JDBC MCP server (PostgreSQL, MySQL, MariaDB, SQLite, SQL Server, Oracle, DB2, DuckDB and more). Use when the user wants to run SQL, inspect schemas/tables/columns/indexes, EXPLAIN a statement, check database health, or wire a database into their agent via MCP. Covers installing and launching the server (jbang, jar, Docker), writing datasources.yaml, and calling its MCP tools and prompts.
license: Apache-2.0
compatibility: Server needs Java 25 (or Docker); drivers download from Maven Central on first use (network required unless the driver cache is warm and --offline is set).
metadata:
  repo: emvnuel/jabuti-mcp
  transport: stdio
---

# jabuti-mcp

Generic JDBC MCP server (Quarkus, stdio transport) that exposes arbitrary
relational databases as MCP tools from a single YAML file. It downloads the
JDBC driver from Maven on demand into `~/.jabuti/drivers/`, loads it in an
isolated class loader, and opens one HikariCP pool per configured source.

## When to use this skill

- The user asks to query, inspect, or health-check a relational database.
- The user wants a database wired into their agent/IDE as an MCP server.
- The task mentions `execute_sql`, `search_objects`, `explain_sql`,
  `health_check`, `database_info`, or the `explore_database` / `er_diagram`
  prompts.

If the server is not installed yet, install it (below), then use its tools.
If the tools are already available in the session, call them directly —
see [references/tools.md](references/tools.md) for arguments and the result
envelope.

## Install and launch

Fastest path (release jar + matching JDK via jbang, no Maven needed):

```bash
jbang jabuti@emvnuel/jabuti-mcp --config=/absolute/path/to/datasources.yaml
```

Alternatives: `mvn package && java -jar target/quarkus-app/quarkus-run.jar`,
or Docker (`docker run -i --rm ... jabuti-mcp:latest` — always `-i`,
never `-t`; a TTY corrupts the JSON-RPC stream).

Register it in the MCP client — per-client snippets live in
[references/clients.md](references/clients.md) (Claude Code, Cursor,
Copilot/VS Code, Claude Desktop, Inspector). Generic stdio entry:

```json
{ "mcpServers": { "jabuti-mcp": {
  "command": "jbang",
  "args": ["jabuti@emvnuel/jabuti-mcp", "--config=/absolute/path/to/datasources.yaml"]
} } }
```

## Configure sources

Copy `datasources.yaml.example` to `datasources.yaml` (never commit it —
passwords stay in environment variables as `${VAR}`). Full reference:
[references/datasources.md](references/datasources.md).

```yaml
sources:
  - id: local_pg
    url: jdbc:postgresql://localhost:5432/myapp
    username: postgres
    password: ${PG_PASSWORD}
    driver: postgres-jdbc
tools:
  - { name: execute_sql, source: local_pg, readonly: true, maxRows: 100 }
  - { name: search_objects, source: local_pg }
```

Built-in driver ids: `postgres-jdbc`, `mysql-jdbc`, `mariadb-jdbc`,
`sqlite-jdbc`, `mssql-jdbc`, `oracle-jdbc`, `db2-jdbc`, `hsqldb-jdbc`,
`derby-jdbc`, `firebird-jdbc`, `hana-jdbc`, `informix-jdbc`, `duckdb-jdbc`.
Anything else goes under a `drivers:` entry with Maven coordinates.

## Working with the tools

- `execute_sql` takes `{"sql": "..."}`; several `;`-separated statements are
  all run, one payload per statement. Results are capped (`maxRows`,
  default 100) — capped payloads carry `"truncated": true`.
- Sources default to **read-only**: only `SELECT`/`WITH` pass (parsed with
  JSqlParser; parse failures, `EXPLAIN`, `SHOW`, `SET`, and data-modifying
  CTEs are denied with `READONLY_VIOLATION`). Writes need
  `readonly: false` on that tool entry — confirm with the user first.
- With two or more sources, tool names are suffixed with the source id
  (`execute_sql_local_pg`). Read-only tools carry `readOnlyHint`, so clients
  can auto-approve them.
- Every result uses the envelope `{"success": true, "data": …}` or
  `{"success": false, "error": …, "code": …}` (failures also set
  `isError: true` on the wire).
- Prefer `search_objects` with progressive disclosure (`names` → `summary`
  → `full`) over dumping whole schemas, and use the `explore_database`
  prompt for guided exploration or `er_diagram` for a Mermaid ER diagram.

## Verify

```bash
npx @modelcontextprotocol/inspector java -Djabuti.config=/absolute/path/to/datasources.yaml -jar target/quarkus-app/quarkus-run.jar
```

Call `execute_sql` with `{"sql": "select 1"}` — success looks like
`{"success": true, "data": {"statements": [...], "source_id": "..."}}`.
No-database smoke test: SQLite + the Chinook sample (see README “Try it
with a sample database”) — driver downloads from Maven Central on first use.
