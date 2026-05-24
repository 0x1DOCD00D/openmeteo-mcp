# OpenMeteo MCP Server in Scala 3

A local Model Context Protocol server written in Scala 3. It exposes selected Open-Meteo web API operations as MCP tools that can be used by Claude Desktop, Claude Code, or another MCP-compatible client.

The project demonstrates how an external web microservice can be wrapped as a typed MCP server. Claude does not inspect the Scala JAR, does not reflect on Scala methods, and does not call Scala functions directly. Claude discovers named MCP tools through the MCP protocol, decides which tool to invoke from the tool names, descriptions, schemas, and user request, and then sends a JSON-RPC `tools/call` request. The Scala server maps that tool name to a Scala handler function.

## What this project demonstrates

- How to expose an external HTTP microservice to an LLM through MCP.
- How to write a local stdio MCP server in Scala 3.
- How to define MCP tools using names, descriptions, input schemas, and output schemas.
- How Claude chooses tools from the metadata returned by `tools/list`.
- How the Scala server dispatches MCP tool calls to internal Scala functions.
- How to connect the server to Claude Desktop.
- How to connect the server to Claude Code.
- How to package the server as a runnable JAR with sbt-assembly.

## External service

This project uses Open-Meteo.

Open-Meteo provides weather APIs over HTTP GET with JSON responses. The example server uses:

- Open-Meteo Geocoding API
- Open-Meteo Forecast API with current weather variables

The default implementation exposes two tools:

- `geocode_city`
- `get_current_weather`

Open-Meteo free API usage is subject to Open-Meteo terms. At the time this README was written, the free API is intended for non-commercial use, has rate limits, and the data is provided under the CC BY 4.0 data license. Check the current Open-Meteo terms before publishing or deploying this project.

Useful links:

- Open-Meteo home: https://open-meteo.com/
- Open-Meteo terms: https://open-meteo.com/en/terms
- Open-Meteo API docs: https://open-meteo.com/en/docs
- Open-Meteo geocoding API: https://open-meteo.com/en/docs/geocoding-api
- CC BY 4.0: https://creativecommons.org/licenses/by/4.0/

## MCP background

MCP stands for Model Context Protocol.

An MCP server exposes capabilities to an MCP client. For this project, the important capability is `tools`.

The MCP flow is:

```text
Claude Desktop or Claude Code
  starts the JAR as a subprocess
  sends JSON-RPC over stdin
  reads JSON-RPC over stdout

Scala MCP server
  reads one JSON-RPC message per line
  answers initialize
  answers tools/list
  answers tools/call
  calls Open-Meteo over HTTP
  returns structured MCP tool results
```

The server is local and uses stdio transport. It is not an HTTP server and does not listen on a port.

## Architecture

```text
User prompt
  |
  v
Claude
  |
  | MCP JSON-RPC over stdio
  v
OpenMeteoMcpServer.scala
  |
  | Java HttpClient over HTTPS
  v
Open-Meteo APIs
```

The critical mapping is:

```text
MCP tool name: geocode_city
  -> Scala ToolDef named geocodeCityTool
  -> Scala handler function geocodeCity

MCP tool name: get_current_weather
  -> Scala ToolDef named currentWeatherTool
  -> Scala handler function getCurrentWeather
```

Claude sees this:

```text
tool name
tool title
tool description
input schema
output schema
server instructions
```

Claude does not see this:

```text
Scala source code
Scala method names
Scala private functions
JAR bytecode internals
```

## Repository structure

Recommended layout:

```text
openmeteo-mcp/
  README.md
  LICENSE
  .gitignore
  build.sbt
  project/
    plugins.sbt
  src/
    main/
      scala/
        example/
          OpenMeteoMcpServer.scala
```

Optional future layout:

```text
openmeteo-mcp/
  src/
    main/
      scala/
        example/
          mcp/
            JsonRpc.scala
            ToolDef.scala
            McpServer.scala
          openmeteo/
            OpenMeteoClient.scala
            OpenMeteoTools.scala
          OpenMeteoMcpServer.scala
    test/
      scala/
        example/
          OpenMeteoMcpServerSpec.scala
```

The current project keeps everything in one file for clarity. That is useful for teaching and for understanding the protocol. For a larger project, split protocol handling, HTTP client logic, tool definitions, and tests.

## Requirements

- JDK 17 or newer
- sbt
- Scala 3
- Claude Desktop or Claude Code
- Internet access to call Open-Meteo APIs

Check Java:

```bash
java -version
```

Check sbt:

```bash
sbt --version
```

## Build files

`project/plugins.sbt`:

```scala
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
```

`build.sbt`:

```scala
ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "example"

lazy val root = (project in file("."))
  .settings(
    name := "openmeteo-mcp",

    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.4.3",

    Compile / mainClass := Some("example.OpenMeteoMcpServer"),
    assembly / mainClass := Some("example.OpenMeteoMcpServer"),
    assembly / assemblyJarName := "openmeteo-mcp.jar"
  )
```

If you use a different Scala version, the generated target directory will change. For example:

```text
target/scala-3.8.3/openmeteo-mcp.jar
target/scala-3.3.7/openmeteo-mcp.jar
```

Use the path that exists on your machine.

## Build the runnable JAR

From the project root:

```bash
sbt clean assembly
```

Expected output:

```text
target/scala-3.8.3/openmeteo-mcp.jar
```

Verify:

```bash
ls -l target/scala-3.8.3/openmeteo-mcp.jar
```

If the Scala target directory is different:

```bash
find target -name "openmeteo-mcp.jar" -print
```

## Smoke test without Claude

Run this from the project root. Adjust the JAR path if needed.

```bash
printf '%s\n' \
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test-client","version":"0.1.0"}}}' \
'{"jsonrpc":"2.0","method":"notifications/initialized"}' \
'{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
'{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"geocode_city","arguments":{"city":"Chicago","count":3}}}' \
| java -jar target/scala-3.8.3/openmeteo-mcp.jar
```

You should see JSON-RPC responses for:

```text
initialize
tools/list
tools/call
```

The `notifications/initialized` message is a notification, so the server should not emit a response for it.

A successful `tools/list` response should include:

```text
geocode_city
get_current_weather
```

## Configure Claude Desktop

Claude Desktop on macOS reads local MCP server configuration from:

```text
~/Library/Application Support/Claude/claude_desktop_config.json
```

Example configuration:

```json
{
  "mcpServers": {
    "openmeteo-scala": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/Users/drmark/IdeaProjects/openmeteo-mcp/target/scala-3.8.3/openmeteo-mcp.jar"
      ],
      "env": {}
    }
  }
}
```

If your current config already has a `preferences` object, keep it and add `mcpServers` at the top level:

```json
{
  "preferences": {
    "remoteToolsDeviceName": "lisbon-local",
    "coworkWebSearchEnabled": true,
    "coworkScheduledTasksEnabled": true,
    "ccdScheduledTasksEnabled": true,
    "epitaxyPrefs": {
      "starred-local-code-sessions": [],
      "starred-cowork-spaces": [],
      "starred-session-groups": [],
      "dframe-local-slice": {
        "pinnedOrder": [],
        "customGroupAssignments": {},
        "customGroupOrder": {}
      }
    }
  },
  "mcpServers": {
    "openmeteo-scala": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/Users/drmark/IdeaProjects/openmeteo-mcp/target/scala-3.8.3/openmeteo-mcp.jar"
      ],
      "env": {}
    }
  }
}
```

After editing the config, fully quit and restart Claude Desktop:

```bash
osascript -e 'quit app "Claude"'
open -a Claude
```

Then ask Claude Desktop:

```text
List the tools exposed by the openmeteo-scala MCP server.
```

Or:

```text
Use the openmeteo-scala MCP server. Find the current weather in Chicago.
```

If Claude says the MCP server is not installed, check that `mcpServers` is present in `claude_desktop_config.json`, the JAR path is absolute, and the JAR exists.

## Configure Claude Code

Claude Code uses its own MCP configuration. The Claude Code CLI command does not automatically modify the Claude Desktop config file.

Add the local stdio server to Claude Code:

```bash
claude mcp add-json openmeteo-scala \
'{"type":"stdio","command":"java","args":["-jar","/Users/drmark/IdeaProjects/openmeteo-mcp/target/scala-3.8.3/openmeteo-mcp.jar"],"env":{}}'
```

Verify:

```bash
claude mcp get openmeteo-scala
claude mcp list
```

Inside Claude Code, use:

```text
/mcp
```

If you want a project-scoped configuration for GitHub publishing, use a `.mcp.json` file at the project root. Example:

```json
{
  "mcpServers": {
    "openmeteo-scala": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "${CLAUDE_PROJECT_DIR:-.}/target/scala-3.8.3/openmeteo-mcp.jar"
      ],
      "env": {}
    }
  }
}
```

Do not commit secrets into `.mcp.json`.

## Tools exposed by the server

## Tool: geocode_city

Purpose:

```text
Resolve a city, place name, or postal code into candidate WGS84 coordinates.
```

MCP name:

```text
geocode_city
```

Scala handler:

```text
geocodeCity
```

Input arguments:

```json
{
  "city": "Chicago",
  "count": 3
}
```

Output shape:

```json
{
  "query": "Chicago",
  "matches": [
    {
      "name": "Chicago",
      "country": "United States",
      "admin1": "Illinois",
      "latitude": 41.85003,
      "longitude": -87.65005,
      "timezone": "America/Chicago"
    }
  ]
}
```

The output is intentionally smaller than the full upstream Open-Meteo response. This helps Claude chain tool calls without dumping unnecessary JSON into the model context.

## Tool: get_current_weather

Purpose:

```text
Get current temperature, wind speed, and weather code for WGS84 coordinates.
```

MCP name:

```text
get_current_weather
```

Scala handler:

```text
getCurrentWeather
```

Input arguments:

```json
{
  "latitude": 41.85003,
  "longitude": -87.65005,
  "timezone": "America/Chicago"
}
```

Output shape:

```json
{
  "latitude": 41.8756,
  "longitude": -87.6244,
  "timezone": "America/Chicago",
  "current": {
    "time": "2026-05-24T13:00",
    "temperature_2m": 72.1,
    "wind_speed_10m": 9.2,
    "weather_code": 2
  }
}
```

Weather values are examples. Actual values depend on Open-Meteo responses at runtime.

## How Claude knows what to call

Claude does not inspect the JAR.

Claude does not know that the Scala method `geocodeCity` exists.

Claude sees only the MCP metadata returned from `tools/list`.

The server advertises tools using this abstraction:

```scala
final case class ToolDef(
    name: String,
    title: String,
    description: String,
    inputSchema: Value,
    outputSchema: Value,
    handler: Obj => Value
)
```

The MCP-visible part is:

```text
name
title
description
inputSchema
outputSchema
```

The Scala-only part is:

```text
handler
```

For example:

```scala
ToolDef(
  name = "geocode_city",
  title = "Geocode city",
  description =
    "Resolve a city or postal-code search string to candidate WGS84 coordinates using Open-Meteo geocoding.",
  inputSchema = ...,
  outputSchema = ...,
  handler = geocodeCity
)
```

Claude sees:

```text
geocode_city
Resolve a city or postal-code search string to candidate WGS84 coordinates.
```

The Scala server keeps:

```text
geocode_city -> geocodeCity
```

The actual dispatcher is:

```scala
toolsByName.get(toolName) match
  case Some(tool) => tool.handler(args)
  case None       => toolError(s"Unknown tool: $toolName")
```

So the real mapping is:

```text
User query
  -> Claude chooses MCP tool from name, description, and schema
  -> Claude sends tools/call with tool name and JSON arguments
  -> Scala server looks up that tool name in toolsByName
  -> Scala server calls the handler function
```

Example user prompt:

```text
What is the current weather in Chicago?
```

Claude sees that:

```text
get_current_weather requires latitude and longitude
geocode_city accepts a city string and returns latitude and longitude
```

So Claude can infer the plan:

```text
Call geocode_city with city = Chicago
Read latitude and longitude from the result
Call get_current_weather with those coordinates
Summarize the weather result
```

## Code structure

The current implementation is intentionally compact and keeps the protocol logic, tool definitions, and HTTP wrappers in one file:

```text
src/main/scala/example/OpenMeteoMcpServer.scala
```

Main sections:

```text
ProtocolVersion
  The MCP protocol version string used by initialize.

httpClient
  Shared Java HttpClient instance.

ToolDef
  Internal Scala representation of an MCP tool.

main
  Starts the stdio loop.

handleLine
  Parses each JSON-RPC message and dispatches by method name.

send
  Writes a compact JSON-RPC response to stdout.

response
  Wraps a successful JSON-RPC result.

error
  Wraps a JSON-RPC protocol error.

initializeResult
  Answers the MCP initialize request.

geocodeCityTool
  MCP metadata plus Scala handler for geocoding.

currentWeatherTool
  MCP metadata plus Scala handler for current weather.

tools
  List of tools exposed by this server.

toolsByName
  Dispatch table from MCP tool name to Scala ToolDef.

listToolsResult
  Builds the response for tools/list.

callTool
  Handles tools/call and invokes the selected handler.

geocodeCity
  Validates city input and calls the Open-Meteo geocoding API.

getCurrentWeather
  Validates coordinates and calls the Open-Meteo forecast API.

httpGetJson
  Performs the upstream HTTP GET and parses JSON.

toolSuccess
  Builds an MCP tool result with content and structuredContent.

toolError
  Builds an MCP tool result with isError = true.

requiredString
  Reads and validates required string arguments.

requiredDouble
  Reads and validates required numeric arguments.

optionalString
  Reads optional string arguments.

optionalInt
  Reads optional integer arguments.

locationSummary
  Normalizes Open-Meteo geocoding results.

jsonStr
  Safely extracts a JSON string field.

jsonNum
  Safely extracts a JSON numeric field.

urlEncode
  Encodes query parameter values.
```

## Protocol flow

A typical MCP session looks like this:

```text
Claude Desktop starts:
  java -jar openmeteo-mcp.jar

Claude sends:
  initialize

Server responds:
  protocolVersion
  capabilities.tools
  serverInfo
  instructions

Claude sends:
  notifications/initialized

Claude sends:
  tools/list

Server responds:
  geocode_city
  get_current_weather

User asks:
  What is the current weather in Chicago?

Claude sends:
  tools/call geocode_city

Server runs:
  geocodeCity(args)

Server calls:
  Open-Meteo geocoding API

Server returns:
  latitude
  longitude
  timezone

Claude sends:
  tools/call get_current_weather

Server runs:
  getCurrentWeather(args)

Server calls:
  Open-Meteo forecast API

Server returns:
  current weather JSON

Claude answers the user.
```

## Why stdout and stderr matter

For stdio MCP servers:

```text
stdout is protocol output
stderr is logs
```

Good:

```scala
Console.err.println("server started")
```

Bad:

```scala
Console.out.println("server started")
```

Claude expects stdout to contain only valid JSON-RPC messages. Random logs on stdout can break the connection.

## Troubleshooting

Problem:

```text
Claude says the server is not installed.
```

Likely cause:

```text
Claude Desktop config does not contain mcpServers.
The server was registered in Claude Code, not Claude Desktop.
The JAR path is wrong.
Claude Desktop was not restarted.
```

Fix:

```bash
cat "$HOME/Library/Application Support/Claude/claude_desktop_config.json"
find /Users/drmark/IdeaProjects/openmeteo-mcp/target -name "openmeteo-mcp.jar" -print
osascript -e 'quit app "Claude"'
open -a Claude
```

Problem:

```text
sbt says: not found: value assembly
```

Likely cause:

```text
project/plugins.sbt is missing or placed under project/project/plugins.sbt.
```

Fix:

```bash
mkdir -p project
cat > project/plugins.sbt <<'EOF'
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
EOF

sbt reload
sbt clean assembly
```

Problem:

```text
Claude sees the server but no tools.
```

Likely causes:

```text
tools/list failed.
initialize failed.
The server printed non-JSON text to stdout.
The JAR crashed on startup.
```

Check logs:

```bash
tail -n 100 "$HOME/Library/Logs/Claude/mcp.log"
tail -n 100 "$HOME/Library/Logs/Claude/mcp-server-openmeteo-scala.log"
```

Problem:

```text
tools/call returns invalid request.
```

Likely cause:

```text
The arguments do not match the expected input schema.
```

Make tool errors actionable. Good error:

```text
Missing required numeric argument: latitude. Use geocode_city first if the user supplied a city name.
```

Bad error:

```text
Bad request.
```

## Design rules for high-quality tool use

Claude's tool-use quality depends heavily on the semantic surface exposed by the MCP server.

Use precise tool names:

```text
Good:
  geocode_city
  get_current_weather
  search_flights
  create_invoice

Bad:
  run
  execute
  method1
  helper
  api_call
```

Use specific descriptions:

```text
Good:
  Resolve a city or postal-code search string to candidate WGS84 coordinates.

Bad:
  Gets data.
```

Use meaningful field names:

```text
Good:
  latitude
  longitude
  departure_airport
  return_date

Bad:
  x
  y
  arg1
  data
```

Use clear field descriptions:

```text
Good:
  Origin airport as a three-letter IATA code, for example ORD.

Bad:
  Origin.
```

Return normalized structured output. Do not dump giant upstream API responses into the model unless the model truly needs them.

Good output chaining:

```text
geocode_city returns latitude and longitude
get_current_weather accepts latitude and longitude
```

Bad output chaining:

```text
tool A returns a giant opaque blob
tool B expects fields hidden somewhere inside the blob
```

## Security model

This project exposes only read-only tools.

The server does not expose arbitrary Scala methods. Claude can call only tools advertised by `tools/list`.

Callable surface:

```scala
tools.map(_.name)
```

Not callable:

```text
private Scala functions that are not attached to a ToolDef
random methods inside the JAR
classes in dependencies
JVM internals
```

Security recommendations before adding write operations:

```text
Validate every input argument.
Validate every structured output.
Use allowlisted upstream domains.
Set HTTP timeouts.
Add rate limits.
Log to stderr, not stdout.
Never expose secrets as tool arguments.
Store API tokens in environment variables.
Use human confirmation for mutating tools.
Use human confirmation for financial or irreversible actions.
Redact sensitive data from logs.
Add OpenTelemetry tracing for production use.
```

## Extending the server

To add a new Open-Meteo tool:

1. Create a Scala handler function.

Example:

```scala
private def getHourlyForecast(args: Obj): Value =
  ???
```

2. Create a `ToolDef`.

Example:

```scala
private lazy val hourlyForecastTool: ToolDef =
  ToolDef(
    name = "get_hourly_forecast",
    title = "Get hourly forecast",
    description = "Get hourly forecast variables for WGS84 coordinates.",
    inputSchema = Obj(
      "type" -> "object",
      "properties" -> Obj(
        "latitude" -> Obj("type" -> "number"),
        "longitude" -> Obj("type" -> "number")
      ),
      "required" -> Arr("latitude", "longitude"),
      "additionalProperties" -> false
    ),
    outputSchema = Obj(
      "type" -> "object",
      "properties" -> Obj(
        "hourly" -> Obj("type" -> "object")
      ),
      "required" -> Arr("hourly"),
      "additionalProperties" -> true
    ),
    handler = getHourlyForecast
  )
```

3. Add it to the tool list.

```scala
private lazy val tools: Seq[ToolDef] =
  Seq(geocodeCityTool, currentWeatherTool, hourlyForecastTool)
```

Rebuild:

```bash
sbt clean assembly
```

Restart Claude Desktop.

## From hand-written wrapper to generator

This project is a hand-written MCP adapter. The same pattern can be generated from an external web service description.

Mapping:

```text
OpenAPI operationId
  -> MCP tool name

OpenAPI summary and description
  -> MCP tool title and description

OpenAPI parameters and request body schema
  -> MCP inputSchema

OpenAPI response schema
  -> MCP outputSchema

HTTP operation
  -> Scala handler

HTTP error
  -> MCP tool result with isError = true
```

For production use, add a semantic annotation layer. OpenAPI tells you field syntax. It often does not tell you enough semantics.

Example:

```text
String is syntax.
IATA airport code is semantics.

Number is syntax.
Latitude in WGS84 is semantics.

String is syntax.
OAuth user identifier is semantics.
```

Without semantic annotations, the LLM may compose structurally valid but semantically wrong calls.

## Testing to add

Recommended tests:

```text
initialize returns tools capability.
tools/list returns expected tools.
geocode_city rejects missing city.
geocode_city returns structuredContent.
get_current_weather rejects invalid latitude.
get_current_weather rejects invalid longitude.
tools/call rejects unknown tool name.
httpGetJson handles non-2xx upstream responses.
stdout contains only JSON-RPC responses.
stderr contains logs.
```

Recommended integration tests:

```text
Run the JAR.
Send initialize.
Send tools/list.
Call geocode_city for Chicago.
Use returned coordinates to call get_current_weather.
Verify structuredContent shape.
```

## Roadmap

Possible next steps:

```text
Split one-file implementation into protocol, client, tools, and app packages.
Add tests.
Add OpenTelemetry spans around every tool call and upstream HTTP call.
Add a generated OpenAPI-to-MCP compiler.
Add support for Streamable HTTP MCP transport.
Add Dockerfile.
Add GitHub Actions build.
Add release workflow.
Add dependency license report.
Add generated documentation for all exposed tools.
Add support for forecast, historical weather, and air quality tools.
```

## Suggested .gitignore

```gitignore
target/
.bsp/
.idea/
.metals/
.bloop/
.vscode/
.DS_Store
*.log
*.class
*.jar
!.jvmopts
```

Do not ignore the `LICENSE` file.

## Publishing checklist

Before publishing on GitHub:

```text
Confirm the project builds with sbt clean assembly.
Confirm the smoke test works.
Confirm Claude Desktop loads the server.
Replace example organization and package names if needed.
Create a LICENSE file.
Add attribution for Open-Meteo data.
Add dependency license notes.
Do not commit secrets.
Do not commit local absolute paths in shared .mcp.json unless they use environment variables.
Add a screenshot or terminal transcript if useful.
Tag the first release.
```

## Project license

Recommended project license: MIT License.

This applies to the Scala MCP server code in this repository, not to Open-Meteo data, Open-Meteo server code, Claude, MCP, sbt, ujson, or other third-party components.

Create a `LICENSE` file with the following text, replacing the copyright holder.

```text
MIT License

Copyright (c) 2026 Mark Grechanik

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files, to deal in the Software
without restriction, including without limitation the rights to use, copy,
modify, merge, publish, distribute, sublicense, and sell copies of the Software,
and to permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED AS IS, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Third-party licenses and attribution

This repository uses or interacts with several external projects and services.

```text
Scala
  Language and ecosystem used to build the server.
  Website: https://www.scala-lang.org/

sbt
  Build tool used for compilation and packaging.
  Website: https://www.scala-sbt.org/

sbt-assembly
  sbt plugin used to create a runnable JAR.
  License: MIT License.
  Website: https://github.com/sbt/sbt-assembly

ujson / uPickle
  JSON parsing and construction library.
  License: MIT License.
  Website: https://github.com/com-lihaoyi/upickle

Model Context Protocol
  Protocol used to expose tools to Claude and other MCP clients.
  Website: https://modelcontextprotocol.io/

Open-Meteo
  Weather and geocoding API provider.
  Data license: CC BY 4.0, subject to Open-Meteo terms.
  Website: https://open-meteo.com/
```

Suggested attribution line for README, UI, or documentation:

```text
Weather data and geocoding data are provided by Open-Meteo.com and are subject to the Open-Meteo terms and CC BY 4.0 data license.
```

For commercial use, higher volume use, or production deployment, review Open-Meteo pricing and terms before using the API.

## Disclaimer

This project is an educational and experimental MCP server. It is not an official Open-Meteo project, not an official Anthropic project, and not an official MCP reference implementation.

The server is intentionally minimal. It does not include production-grade authentication, rate limiting, observability, retries, caching, or schema-drift detection. Add those before using this pattern in production.
