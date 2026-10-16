# datasources.yaml reference

Path resolution: working directory → `--config=<path>` →
`JABUTI_CONFIG` env → `-Djabuti.config=...`. Unknown keys anywhere are
rejected at startup (the error lists allowed keys). Never commit this file;
passwords come from the environment as `${VAR}`.

```yaml
sources:
  - id: local_pg
    url: jdbc:postgresql://localhost:5432/myapp
    username: postgres
    password: ${PG_PASSWORD}     # interpolated from the environment
    driver: postgres-jdbc        # built-in id, or an id from `drivers:`
    # poolMaxConnections: 10     # optional (default 10; forced to 1 for jdbc:sqlite:)

repositories:                    # optional; when present it REPLACES Maven Central
  - id: internal                 # optional (default "central")
    url: https://artifactory.corp.example/artifactory/maven-virtual/
    username: ${MAVEN_USER}      # optional; HTTP basic auth
    password: ${MAVEN_PASS}

drivers:                         # only for DBs outside the built-in catalog
  - id: exotico-jdbc
    className: com.exotico.Driver
    repository: internal         # optional; resolve from one repo only
    maven: [{ groupId: com.exotico, artifactId: driver, version: "1.2.3" }]

tools:                           # omit to default to execute_sql + search_objects
  - { name: execute_sql, source: local_pg, readonly: true, maxRows: 100 }
  - { name: search_objects, source: local_pg }
  - { name: explain_sql, source: local_pg }   # opt-in
  - { name: health_check, source: local_pg }  # opt-in
  - { name: database_info, source: local_pg } # opt-in
```

- Built-in drivers: `postgres-jdbc`, `mysql-jdbc`, `mariadb-jdbc`,
  `sqlite-jdbc`, `mssql-jdbc`, `oracle-jdbc`, `db2-jdbc`, `hsqldb-jdbc`,
  `derby-jdbc`, `firebird-jdbc`, `hana-jdbc`, `informix-jdbc`, `duckdb-jdbc`
  (pinned in `src/main/resources/builtin-drivers.yaml`). A `drivers:` entry
  with the same id overrides the version; omit `className` to inherit it.
- `readonly` defaults to `true`, `maxRows` to `100` (tool-level settings).
- Driver cache: `~/.jabuti/drivers/<group>/<artifact>/<version>/`
  (override with `-Djabuti.drivers-dir=`); a cached jar is never
  re-downloaded. `--offline` / `-Djabuti.offline=true` resolves from cache
  only.
- Private repos: credentials via `${VAR}`; `file://` URLs work
  (`file:///srv/maven-mirror/`). Maven `settings.xml` and proxies are not
  supported — the JVM proxy properties are ignored by the resolver.
- CLI flags: `--config=<path>`, `--offline`, `--debug` (MCP traffic on
  stderr), `--help`. Stdio is the MCP channel: keep stdout clean.
