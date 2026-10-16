# Registering jabuti-mcp in MCP clients

All clients speak stdio. Use absolute paths everywhere — GUI-spawned clients
do not inherit the shell `PATH` or environment.

## Claude Code

```bash
claude mcp add jabuti-mcp -- jbang jabuti@emvnuel/jabuti-mcp --config=/absolute/path/to/datasources.yaml
```

With a local build, or to pass secrets without touching the YAML:

```bash
claude mcp add jabuti-mcp -e PG_PASSWORD=... -- java -Djabuti.config=/absolute/path/to/datasources.yaml -jar /absolute/path/to/jabuti-mcp/target/quarkus-app/quarkus-run.jar
```

Project-scoped instead of global: add `-s project` (writes `.mcp.json`).

## Cursor

Settings → MCP → Add, or `.cursor/mcp.json` in the project:

```json
{ "mcpServers": { "jabuti-mcp": {
  "command": "jbang",
  "args": ["jabuti@emvnuel/jabuti-mcp", "--config=/absolute/path/to/datasources.yaml"]
} } }
```

## Copilot / VS Code

`.vscode/mcp.json` in the project:

```json
{ "servers": { "jabuti-mcp": {
  "type": "stdio",
  "command": "jbang",
  "args": ["jabuti@emvnuel/jabuti-mcp", "--config=/absolute/path/to/datasources.yaml"]
} } }
```

Then `MCP: List Servers` → Start. Secrets go in the `env` block or the
shell that launches VS Code — never inline in committed files.

## Claude Desktop

`claude_desktop_config.json`, same `mcpServers` shape as Cursor. Prefer the
`env` block over `-e` args for `${VAR}` values the YAML interpolates.

## Docker (any client)

```json
{ "mcpServers": { "jabuti-mcp": {
  "command": "/usr/bin/docker",
  "args": ["run", "-i", "--rm",
    "-v", "/ABS/PATH/datasources.yaml:/config/datasources.yaml:ro",
    "-v", "jabuti-drivers:/home/jabuti/.jabuti",
    "-e", "JABUTI_CONFIG=/config/datasources.yaml",
    "-e", "PG_PASSWORD",
    "ghcr.io/emvnuel/jabuti-mcp:latest"]
} } }
```

Absolute docker path (`which docker`); `-i`, never `-t`. DB on the host:
`host.docker.internal` (Desktop) or `--add-host`/`--network host` (Linux);
DB in a container: same `--network`, hostname = container name.

## MCP Inspector (debug)

```bash
npx @modelcontextprotocol/inspector java -Djabuti.config=/absolute/path/to/datasources.yaml -jar target/quarkus-app/quarkus-run.jar
```
