# jabuti-mcp

A generic JDBC MCP server (stdio) built with Quarkus: it exposes arbitrary
relational databases as MCP tools from a single YAML file.

## Language

**Source**:
A named database connection from `datasources.yaml` — driver id, JDBC URL,
credentials, optional pool size. The server keeps one connection pool per
source.
_Avoid_: datasource, database (when the configured entry is meant)

**Driver catalog**:
The built-in driver ids merged with the YAML `drivers:` entries; an id
resolves to Maven coordinates, a downloaded jar and a loaded class.
_Avoid_: driver registry, driver list

**Object type**:
One of schema, table, view, column, index — the kinds `search_objects` can
find. procedure/function are reserved: generic JDBC has no portable routine
API.
_Avoid_: object kind, entity type

**Detail level**:
How much of each found object a search returns: `names` (name and context
only), `summary` (counts and column material), `full` (summary plus index
material).
_Avoid_: verbosity, depth

**Statement**:
One SQL statement inside a tool call; `execute_sql` accepts several separated
by `;` and returns one payload per statement.
_Avoid_: query (when any statement is meant), command

**Statement batch**:
The statements parsed from a single tool call. Classification is lazy — a
statement's class is parsed on first ask and cached.
_Avoid_: script, SQL block

**Statement class**:
One of READ, DML, DDL, ADMIN, UNKNOWN — ordered by privilege, UNKNOWN
highest. The read-only gate admits only READ.
_Avoid_: statement type, statement kind

**Read-only gate**:
The rule that a readonly source admits only READ statements; everything else
— including statements the parser cannot classify — is denied with
`READONLY_VIOLATION`.
_Avoid_: readonly check

**Tool envelope**:
The uniform tool result: `{"success":true,"data":…}` or
`{"success":false,"error":…,"code":…}`.
_Avoid_: response, wrapper

**Tool error**:
An expected tool failure carrying the envelope code the client receives
(`READONLY_VIOLATION`, `INVALID_INPUT`, `UNSUPPORTED`, …). Anything untyped
is an unexpected failure and receives the tool's default code.
_Avoid_: exception, throwable

**Prompt**:
An MCP feature registered at startup like tools but per deployment, not
per source: `explore_database` and `er_diagram`. An optional `source` argument
disambiguates when several sources exist.
_Avoid_: template, workflow

**Database info**:
The opt-in `database_info` tool: engine dialect hints (product/version,
identifier quoting, catalog/schema terms, transaction support, SQL keywords)
read from `DatabaseMetaData`; unavailable values degrade to `notes`.
_Avoid_: dialect report, metadata dump
