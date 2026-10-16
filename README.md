# jabuti-mcp

A generic JDBC MCP server (stdio transport) built with Quarkus — named after
the Brazilian tortoise that carries its home on its back: jabuti-mcp carries
its own database drivers. It exposes arbitrary relational databases to MCP
clients (Claude Desktop, Cursor, MCP Inspector, ...) from a single YAML file:
give it a JDBC URL plus a driver id and it downloads the driver from the
configured Maven repositories on demand into `~/.jabuti/drivers/`, loads it in
an isolated class loader and opens one HikariCP pool per source.

## Requirements

- Java 25 (JVM only; native images are out of scope because drivers are loaded
  dynamically at runtime)
- Maven 3.9+
- Docker (`mvn verify` uses Testcontainers; the [Docker](#docker) section also
  offers an optional way to build and run the server without a host Java
  install)

### Install with jbang (no Maven needed)

Once a release is published (the release workflow does this on a `v*` tag):

```bash
jbang jabuti@emvnuel/jabuti-mcp --config=/absolute/path/to/datasources.yaml
```

jbang downloads the single-file jar from the latest GitHub release and, if
needed, a matching JDK. The Claude Desktop config below works the same way with
`"command": "jbang"` and `"args": ["jabuti@emvnuel/jabuti-mcp", "--config=/absolute/path/to/datasources.yaml"]`.

### Use from AI agents (Agent Skill)

The repo ships an [Agent Skill](https://agentskills.io) that tells your agent
when and how to use this server — install it straight into Claude Code,
Cursor, Copilot and 20+ other agents:

```bash
npx skills add emvnuel/jabuti-mcp
```

The skill (`.agents/skills/jabuti-mcp/SKILL.md`) covers launching the server,
writing `datasources.yaml`, per-client setup (Claude Code, Cursor,
Copilot/VS Code, Claude Desktop, Inspector) and the tool/prompt catalog.

## Configuration

Copy `datasources.yaml.example` to `datasources.yaml` and adjust it. The path is
resolved against the working directory; override it with
`--config=/path/to/datasources.yaml`, the `JABUTI_CONFIG` environment variable
or `-Djabuti.config=/path/to/datasources.yaml`.

```yaml
sources:
  - id: local_pg
    url: jdbc:postgresql://localhost:5432/myapp
    username: postgres
    password: ${PG_PASSWORD}     # ${VAR} is interpolated from the environment
    driver: postgres-jdbc        # built-in catalog id, or an id from `drivers:`
    # poolMaxConnections: 10     # optional (default 10; forced to 1 for jdbc:sqlite:)

repositories:                    # optional; when present it REPLACES Maven Central
  - id: internal                 # optional (default "central"); see below
    url: https://artifactory.corp.example/artifactory/maven-virtual/
    username: ${MAVEN_USER}      # optional; sent as HTTP basic auth
    password: ${MAVEN_PASS}

drivers:                         # only for databases outside the built-in catalog
  - id: exotico-jdbc
    className: com.exotico.Driver
    repository: internal         # optional; resolve this driver from one repo only
    maven: [{ groupId: com.exotico, artifactId: driver, version: "1.2.3" }]

tools:                           # omit entirely to default to execute_sql + search_objects
  - { name: execute_sql, source: local_pg, readonly: true, maxRows: 100 }
  - { name: search_objects, source: local_pg }
  - { name: explain_sql, source: local_pg }   # opt-in
  - { name: health_check, source: local_pg }  # opt-in
  - { name: database_info, source: local_pg } # opt-in
```

- Built-in drivers: `postgres-jdbc`, `mysql-jdbc`, `mariadb-jdbc`,
  `sqlite-jdbc`, `mssql-jdbc`, `oracle-jdbc`, `db2-jdbc`, `hsqldb-jdbc`,
  `derby-jdbc`, `firebird-jdbc`, `hana-jdbc`, `informix-jdbc`, `duckdb-jdbc`
  (pinned versions in `src/main/resources/builtin-drivers.yaml`). A `drivers:`
  entry with the same id overrides the version; omit `className` to inherit
  it.
- Liveness probes (run on every use of a source's pool, not only when it is
  created, and by `health_check`) are engine-aware: a FROM-less `SELECT 1`
  where the grammar allows it, otherwise a constant from the engine's dummy
  table — `SELECT 1 FROM DUAL` on Oracle, `SELECT 1 FROM SYSIBM.SYSDUMMY1` on
  DB2 and Derby, and the equivalents on Firebird, HANA, HSQLDB and Informix.
- `repositories:` is optional; when present drivers come only from the
  declared repositories (see [Private Maven repositories](#private-maven-repositories)).
  A `drivers:` entry may pin one with `repository: <id>`.
- `readonly` and `maxRows` are tool-level settings (`readonly` defaults to
  `true`, `maxRows` to `100`). With `readonly: true`, SQL is parsed with
  JSqlParser and only `SELECT`/`WITH` statements pass — everything else,
  including parse failures, `EXPLAIN`, `SHOW` and `SET`, is denied with
  `READONLY_VIOLATION`. Statements whose `WITH` bodies modify data
  (PostgreSQL data-modifying CTEs such as `WITH x AS (DELETE ...) SELECT ...`)
  are denied too.
- Statements are split with the target's lexer rules: MySQL/MariaDB sources
  honor backslash escapes in string literals, so the read-only gate and the
  executor see the same statements as the server.
- Unknown keys anywhere in the file are rejected at startup, listing the
  allowed keys.
- Never commit `datasources.yaml` (it is git-ignored); keep passwords in
  environment variables.

## Build and run

```bash
mvn package
java -jar target/quarkus-app/quarkus-run.jar
```

CLI options (all optional; stdio is the MCP channel, so help and logs go to
stderr):

| Flag | Meaning |
| --- | --- |
| `--config=<path>` | YAML to load (same as `JABUTI_CONFIG` / `-Djabuti.config=...`) |
| `--offline` | Resolve drivers from the cache only (same as `-Djabuti.offline=true`) |
| `--debug` | Log MCP traffic and enable DEBUG client logging |
| `--help`, `-h` | Usage on stderr |

`mvn -Pdist package` builds a single-file uber-jar
(`target/jabuti-mcp-<version>-runner.jar`) for jbang/GitHub Releases; the
release workflow attaches it to every `v*` tag.

The server speaks MCP over stdio; all logs go to stderr. `tools/list` shows one
tool per source: with a single source the names are bare (`execute_sql`,
`search_objects`, ...); with two or more they are suffixed with the normalized
source id (`execute_sql_local_pg`). Read-only tools carry MCP annotations
(`readOnlyHint`), so clients can auto-approve them; write-enabled `execute_sql`
carries `destructiveHint`. Failed calls return the error envelope **and**
`isError: true` on the wire.

### Claude Desktop

```json
{
  "mcpServers": {
    "jabuti-mcp": {
      "command": "java",
      "args": [
        "-Djabuti.config=/absolute/path/to/datasources.yaml",
        "-jar",
        "/absolute/path/to/jabuti-mcp/target/quarkus-app/quarkus-run.jar"
      ]
    }
  }
}
```

`JABUTI_CONFIG` can be passed via the `env` block instead of the system
property.

### MCP Inspector (E2E)

```bash
npx @modelcontextprotocol/inspector java -Djabuti.config=/absolute/path/to/datasources.yaml -jar target/quarkus-app/quarkus-run.jar
```

Call `execute_sql` with `{"sql": "select 1"}` — a successful result is
`{"success": true, "data": {"statements": [...], "source_id": "..."}}`.

### Try it with a sample database

The Chinook sample (a music store: artists, albums, tracks, invoices) runs
against SQLite with no server to install; the `sqlite-jdbc` driver is
downloaded from Maven Central on the first tool call:

```bash
curl -L -o Chinook_Sqlite.sqlite \
  https://github.com/lerocha/chinook-database/raw/master/ChinookDatabase/DataSources/Chinook_Sqlite.sqlite
cat > datasources.yaml <<'EOF'
sources:
  - id: chinook
    url: "jdbc:sqlite:Chinook_Sqlite.sqlite"
    driver: sqlite-jdbc
EOF
npx @modelcontextprotocol/inspector java -jar target/quarkus-app/quarkus-run.jar
```

Ask the model to explore the schema (or invoke the `explore_database` prompt):
it will call `search_objects` and `execute_sql` against the file. The
[Northwind](https://github.com/jpwhite3/northwind-SQLite3) and
[Netflix](https://github.com/lerocha/netflixdb) SQLite databases work the same
way.

## Docker

Docker is an optional alternative to the host Java install: the image carries
its own JRE, so a machine running the server needs neither Java 25 nor Maven.
The container speaks MCP over stdio — run it with `-i`, never `-t` (a TTY
corrupts the JSON-RPC stream). Release images published to ghcr.io are
multi-arch (`linux/amd64` and `linux/arm64`).

### Build

```bash
docker build -t jabuti-mcp:latest .
```

### Smoke run against a containerized Postgres

```bash
docker network create jabuti-mcp-dev || true   # "already exists" is fine
docker run -d --rm --name jabuti-mcp-pg --network jabuti-mcp-dev \
  -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app -e POSTGRES_DB=app \
  postgres:16-alpine
# the "--" keeps the Inspector from consuming docker's own -e flags
npx @modelcontextprotocol/inspector -- docker run -i --rm --network jabuti-mcp-dev \
  -v /ABS/PATH/datasources.docker.yaml:/config/datasources.yaml:ro \
  -v jabuti-drivers:/home/jabuti/.jabuti \
  -e JABUTI_CONFIG=/config/datasources.yaml \
  -e PG_PASSWORD=app \
  jabuti-mcp:latest
```

Create `datasources.docker.yaml` (copy `datasources.yaml.example`) with the
sibling container as host:

```yaml
sources:
  - id: local_pg
    url: jdbc:postgresql://jabuti-mcp-pg:5432/app
    username: app
    password: ${PG_PASSWORD}
    driver: postgres-jdbc
tools:
  - { name: execute_sql, source: local_pg, readonly: true, maxRows: 100 }
  - { name: search_objects, source: local_pg }
```

As in the host run, the first tool call that touches a source downloads its
driver (see Drivers and offline mode below).

When done, stop the database and drop the network —
`docker stop jabuti-mcp-pg && docker network rm jabuti-mcp-dev`. The driver volume
keeps its cache.

### Claude Desktop

```json
{
  "mcpServers": {
    "jabuti-mcp": {
      "command": "/usr/local/bin/docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/ABS/PATH/datasources.yaml:/config/datasources.yaml:ro",
        "-v", "jabuti-drivers:/home/jabuti/.jabuti",
        "-e", "JABUTI_CONFIG=/config/datasources.yaml",
        "-e", "PG_PASSWORD",
        "jabuti-mcp:latest"
      ]
    }
  }
}
```

- Use the absolute path to `docker` (check with `which docker`):
  `/usr/local/bin/docker` with Docker Desktop on macOS,
  `/opt/homebrew/bin/docker` with Homebrew/Colima, `/usr/bin/docker` on
  Linux. GUI-spawned clients do not inherit the shell `PATH`.
- Forward every variable the YAML interpolates (`${PG_PASSWORD}`) with
  `-e VAR` in the args. GUI-spawned clients do not inherit the shell
  environment either: export the variable into the GUI session before
  launching the client (on macOS: `launchctl setenv PG_PASSWORD ...`, valid
  until the next reboot). The client's own `env` block and an inline
  `-e PG_PASSWORD=...` also work, but store the value in plain text in the
  client config file.
- Inside the image `JABUTI_CONFIG` already points at
  `/config/datasources.yaml`; set it only if you mount the file elsewhere.

### Networking

- Database on the host, Docker Desktop (macOS/Windows): use
  `host.docker.internal` in the JDBC URL.
- Database on the host, Linux: add
  `--add-host=host.docker.internal:host-gateway`, or use `--network host`
  (then the JDBC URL host is `localhost`).
- Database as a container: join the same network (`--network`) and use the
  container name as host.

### Drivers and offline mode

- The first use of a driver downloads it once from Maven Central (or from the
  `repositories:` section of the YAML) into the named volume
  `jabuti-drivers` (mounted at `/home/jabuti/.jabuti`); later sessions
  reuse it.
- Private repositories: forward the credentials the YAML interpolates with
  `-e MAVEN_USER -e MAVEN_PASS` (same rule as `${PG_PASSWORD}`); the `${VAR}`
  placeholders then resolve inside the container.
- Offline: `-e JAVA_TOOL_OPTIONS=-Djabuti.offline=true` (the volume must be
  warm). This replaces the image's default `JAVA_TOOL_OPTIONS`; to keep the
  memory cap too, pass
  `-e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djabuti.offline=true"`.

## Tools

| Tool | Args | Notes |
| --- | --- | --- |
| `execute_sql` | `sql` | Multiple statements separated by `;`; one payload per statement; capped results carry `"truncated": true` |
| `search_objects` | `object_type` (schema/table/view/column/procedure/function/index), `pattern`, `schema`, `table`, `detail_level` (names/summary/full), `limit` | Always read-only; progressive disclosure |
| `explain_sql` (opt-in) | `sql` | Never executes the target statement; `EXPLAIN QUERY PLAN` on SQLite; engines without a supported EXPLAIN form return `UNSUPPORTED` |
| `health_check` (opt-in) | — | Pool state + engine-aware liveness probe (reported as `probe.select1_ok`) + database version; engine-specific metrics degrade to `notes` |
| `database_info` (opt-in) | — | Product/version, driver, redacted URL, identifier quoting, catalog/schema terms, transaction support, SQL keywords; each unavailable value degrades to `notes` |

Errors are always returned as tool output:
`{"success": false, "error": "...", "code": "READONLY_VIOLATION" | ...}`, and
the MCP response also carries `isError: true` so clients render the failure.

## Prompts

Registered once per deployment (not per source); the optional `source`
argument disambiguates when several sources are configured:

| Prompt | Args | Purpose |
| --- | --- | --- |
| `explore_database` | `source` (optional) | Guided schema exploration via progressive disclosure (`search_objects` names → summary → full) |
| `er_diagram` | `source` (optional) | Build a Mermaid ER diagram from the schema (relationships inferred from naming and keys — foreign keys are not reported yet) |

## Driver cache and offline mode

- Cache: `~/.jabuti/drivers/<group>/<artifact>/<version>/`; an existing jar is
  never downloaded again. Override with `-Djabuti.drivers-dir=/path`.
- Offline: `-Djabuti.offline=true` uses only the cache and fails with a clear
  message when a driver is missing.

### Private Maven repositories

By default drivers come from Maven Central. Declare a `repositories:` section to
use corporate repositories instead — the section **replaces** Central (there is
no implicit fallback; declare Central explicitly to keep it in the list):

```yaml
repositories:
  - id: internal                       # optional, default "central"
    url: https://artifactory.corp.example/artifactory/maven-virtual/
    username: ${MAVEN_USER}            # optional; sent as HTTP basic auth
    password: ${MAVEN_PASS}
  - id: central                        # a Central mirror; the id keeps caches warm
    url: https://repo.maven.apache.org/maven2/
```

- The URL is the full repository base; nothing (like `/maven2`) is appended.
  Nexus example: `https://nexus.corp.example/repository/maven-public/`.
- `username`/`password` are optional; without them the repository is anonymous.
  `${VAR}` interpolates from the environment like datasource passwords: an
  unresolved variable in `url` fails at startup, while in `username`/`password`
  it is kept literally and authentication fails at download time.
- A `drivers:` entry may pin one repository with `repository: <id>`; the id must
  be declared (or the implicit `central` when the section is absent). Dangling
  references, duplicate repository ids, unknown keys, non-`http(s)`/`file` URLs,
  blank `repository:` values and credentials embedded in the URL are rejected
  at startup.
- Only the declared repositories are consulted: `<repositories>` sections
  inside dependency POMs are ignored, so no host outside this list is
  contacted.
- `file://` mirrors work too, e.g. `url: file:///srv/maven-mirror/`.
- Cache entries are tagged with the id of the repository they came from
  (`_remote.repositories`): a driver cached from `central` is not reused when
  the same coordinates are requested from a different id — online it is
  downloaded again, offline resolution fails. Use `id: central` for a Central
  mirror to keep an existing cache warm.
- Maven `settings.xml` (`<mirrors>`, `<servers>`) and proxy configuration are
  **not** supported yet: the JVM proxy system properties (`-Dhttp(s).proxyHost`)
  are ignored by the driver resolver, so credentials belong in `datasources.yaml`
  via `${VAR}`.

## Testing

```bash
mvn test     # unit tests (the driver resolver test downloads a driver once)
mvn verify   # + ServerIT: PostgreSQL 16 and MySQL 8.4 via Testcontainers
```

`ServerIT` boots the packaged server over stdio against both containers and
checks `tools/list` (including tool titles/annotations), a `SELECT`,
`READONLY_VIOLATION` on `DELETE` (and `isError: true` on the wire),
`truncated: true` beyond `maxRows`, `search_objects` with `detail_level: full`,
`explain_sql` (including that the target statement is never executed),
`health_check` and `database_info` on PostgreSQL, `prompts/list` +
`prompts/get`, and the automatic driver download into a temporary drivers
directory.

## Known limitations (MVP)

- The SQL splitter does not treat PostgreSQL dollar-quoted bodies (`$$...$$`)
  as quoted blocks; a `;` inside such a body in a multi-statement
  `execute_sql` call may split incorrectly.
- `search_objects` returns an empty list with a `notes` entry for
  `procedure`/`function`: generic JDBC has no portable routine API.
- Foreign keys are not reported (yet): the `er_diagram` prompt infers
  relationships from column naming, primary keys and unique indexes.
- `health_check` exposes pool/probe/product info only; engine-specific metrics
  (buffer cache, sessions) are planned for a later phase.
- Driver resolution reads repositories from `datasources.yaml` only: Maven
  `settings.xml` (`<mirrors>`, `<servers>`) and proxies are not supported yet.
- Only the stdio transport is implemented.
