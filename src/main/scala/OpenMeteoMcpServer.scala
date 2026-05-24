/*
 * ================================================================================
 *   Lone Star Consulting, Inc. / Mark Grechanik
 *   File: OpenMeteoMcpServer.scala
 *   Project: openmeteo-mcp
 *   Module: openmeteo-mcp.main
 *   Created: 24 May 2026 13:45
 *   Last Modified: 24 May 2026 13:32
 * ================================================================================
 *
 * Copyright (c) 2026 Lone Star Consulting, Inc. and Mark Grechanik.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Indemnity and hold harmless notice:
 * Any party that offers support, warranty, indemnity, deployment, hosting,
 * integration, modification, redistribution, or other liability obligations
 * for this software does so solely on its own behalf and at its own risk.
 * Such party shall fully indemnify, defend, and hold harmless Lone Star
 * Consulting, Inc., Mark Grechanik, copyright holders, and contributors from
 * all claims, losses, liabilities, damages, costs, and expenses arising from
 * that party's use, modification, redistribution, support, warranty,
 * indemnity, deployment, hosting, integration, or other assumption of
 * liability for this software.
 *
 * ================================================================================
 *   End of notice. Proceed responsibly. Bugs dislike sunlight.
 * ================================================================================
 */


import ujson.*

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.{Try}
import scala.util.control.NonFatal

object OpenMeteoMcpServer:

  private val ProtocolVersion = "2025-11-25"

  private val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()

  final case class ToolDef(
                            name: String,
                            title: String,
                            description: String,
                            inputSchema: Value,
                            outputSchema: Value,
                            handler: Obj => Value
                          ):
    def asJson: Value =
      Obj(
        "name" -> name,
        "title" -> title,
        "description" -> description,
        "inputSchema" -> inputSchema,
        "outputSchema" -> outputSchema
      )

  def main(args: Array[String]): Unit =
    // Never write logs to stdout in a stdio MCP server.
    // stdout is reserved for JSON-RPC protocol messages.
    Console.err.println(s"openmeteo-mcp starting on stdio, MCP $ProtocolVersion")

    val source = scala.io.Source.stdin

    try
      for line <- source.getLines() do
        val trimmed = line.trim
        if trimmed.nonEmpty then handleLine(trimmed)
    catch
      case NonFatal(e) =>
        Console.err.println(s"server loop failed: ${e.getMessage}")

  private def handleLine(line: String): Unit =
    try
      val msg = ujson.read(line)
      val obj = msg.obj

      val idOpt: Option[Value] = obj.get("id")
      val method: String =
        obj.get("method").collect { case Str(s) => s }.getOrElse("")

      method match
        case "initialize" =>
          idOpt.foreach(id => send(response(id, initializeResult(msg))))

        case "notifications/initialized" =>
          // Notification: no response.
          ()

        case "ping" =>
          idOpt.foreach(id => send(response(id, Obj())))

        case "tools/list" =>
          idOpt.foreach(id => send(response(id, listToolsResult())))

        case "tools/call" =>
          idOpt.foreach { id =>
            val result = callTool(msg)
            send(response(id, result))
          }

        case other =>
          idOpt.foreach(id => send(error(id, -32601, s"Method not found: $other")))

    catch
      case NonFatal(e) =>
        // If parsing failed, we may not have an id to respond to safely.
        Console.err.println(s"bad request: ${e.getMessage}")

  private def send(v: Value): Unit =
    // Compact JSON keeps each JSON-RPC message on exactly one line.
    Console.out.println(ujson.write(v))
    Console.out.flush()

  private def response(id: Value, result: Value): Value =
    Obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> result
    )

  private def error(id: Value, code: Int, message: String): Value =
    Obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "error" -> Obj(
        "code" -> code,
        "message" -> message
      )
    )

  private def initializeResult(msg: Value): Value =
    val requested =
      Try(msg("params")("protocolVersion").str).getOrElse(ProtocolVersion)

    val negotiated =
      if requested == ProtocolVersion then requested else ProtocolVersion

    Obj(
      "protocolVersion" -> negotiated,
      "capabilities" -> Obj(
        "tools" -> Obj(
          "listChanged" -> false
        )
      ),
      "serverInfo" -> Obj(
        "name" -> "openmeteo-scala-mcp",
        "title" -> "Open-Meteo Scala MCP Server",
        "version" -> "0.1.0"
      ),
      "instructions" ->
        "Use geocode_city to convert a place name into coordinates, then use get_current_weather."
    )

  private lazy val geocodeCityTool: ToolDef =
    ToolDef(
      name = "geocode_city",
      title = "Geocode city",
      description =
        "Resolve a city or postal-code search string to candidate WGS84 coordinates using Open-Meteo geocoding.",
      inputSchema = Obj(
        "type" -> "object",
        "properties" -> Obj(
          "city" -> Obj(
            "type" -> "string",
            "description" -> "City, place name, or postal code to search for."
          ),
          "count" -> Obj(
            "type" -> "integer",
            "description" -> "Maximum number of matches to return.",
            "minimum" -> 1,
            "maximum" -> 10,
            "default" -> 5
          )
        ),
        "required" -> Arr("city"),
        "additionalProperties" -> false
      ),
      outputSchema = Obj(
        "type" -> "object",
        "properties" -> Obj(
          "query" -> Obj("type" -> "string"),
          "matches" -> Obj(
            "type" -> "array",
            "items" -> Obj(
              "type" -> "object",
              "properties" -> Obj(
                "name" -> Obj("type" -> Arr("string", "null")),
                "country" -> Obj("type" -> Arr("string", "null")),
                "admin1" -> Obj("type" -> Arr("string", "null")),
                "latitude" -> Obj("type" -> Arr("number", "null")),
                "longitude" -> Obj("type" -> Arr("number", "null")),
                "timezone" -> Obj("type" -> Arr("string", "null"))
              ),
              "required" -> Arr(
                "name",
                "country",
                "admin1",
                "latitude",
                "longitude",
                "timezone"
              ),
              "additionalProperties" -> false
            )
          )
        ),
        "required" -> Arr("query", "matches"),
        "additionalProperties" -> false
      ),
      handler = geocodeCity
    )

  private lazy val currentWeatherTool: ToolDef =
    ToolDef(
      name = "get_current_weather",
      title = "Get current weather",
      description =
        "Get current temperature, wind speed, and weather code for WGS84 coordinates using Open-Meteo.",
      inputSchema = Obj(
        "type" -> "object",
        "properties" -> Obj(
          "latitude" -> Obj(
            "type" -> "number",
            "description" -> "WGS84 latitude.",
            "minimum" -> -90,
            "maximum" -> 90
          ),
          "longitude" -> Obj(
            "type" -> "number",
            "description" -> "WGS84 longitude.",
            "minimum" -> -180,
            "maximum" -> 180
          ),
          "timezone" -> Obj(
            "type" -> "string",
            "description" -> "Timezone for the response, for example auto or Europe/Berlin.",
            "default" -> "auto"
          )
        ),
        "required" -> Arr("latitude", "longitude"),
        "additionalProperties" -> false
      ),
      outputSchema = Obj(
        "type" -> "object",
        "properties" -> Obj(
          "latitude" -> Obj("type" -> Arr("number", "null")),
          "longitude" -> Obj("type" -> Arr("number", "null")),
          "timezone" -> Obj("type" -> Arr("string", "null")),
          "current" -> Obj(
            "type" -> "object",
            "properties" -> Obj(
              "time" -> Obj("type" -> Arr("string", "null")),
              "temperature_2m" -> Obj("type" -> Arr("number", "null")),
              "wind_speed_10m" -> Obj("type" -> Arr("number", "null")),
              "weather_code" -> Obj("type" -> Arr("number", "null"))
            ),
            "required" -> Arr(
              "time",
              "temperature_2m",
              "wind_speed_10m",
              "weather_code"
            ),
            "additionalProperties" -> false
          )
        ),
        "required" -> Arr("latitude", "longitude", "timezone", "current"),
        "additionalProperties" -> false
      ),
      handler = getCurrentWeather
    )

  private lazy val tools: Seq[ToolDef] =
    Seq(geocodeCityTool, currentWeatherTool)

  private lazy val toolsByName: Map[String, ToolDef] =
    tools.map(t => t.name -> t).toMap

  private def listToolsResult(): Value =
    Obj(
      "tools" -> Arr(tools.map(_.asJson)*)
    )

  private def callTool(msg: Value): Value =
    try
      val params = msg("params")
      val toolName = params("name").str

      val args: Obj =
        params.obj.get("arguments") match
          case Some(o: Obj) => o
          case Some(_)      => Obj()
          case None         => Obj()

      toolsByName.get(toolName) match
        case Some(tool) => tool.handler(args)
        case None       => toolError(s"Unknown tool: $toolName")

    catch
      case NonFatal(e) =>
        toolError(s"Invalid tools/call request: ${e.getMessage}")

  private def geocodeCity(args: Obj): Value =
    requiredString(args, "city") match
      case Left(problem) =>
        toolError(problem)

      case Right(city) =>
        val count =
          math.max(1, math.min(optionalInt(args, "count", 5), 10))

        val url =
          "https://geocoding-api.open-meteo.com/v1/search" +
            s"?name=${urlEncode(city)}" +
            s"&count=$count" +
            "&language=en" +
            "&format=json"

        httpGetJson(url) match
          case Left(problem) =>
            toolError(problem)

          case Right(json) =>
            val rawResults: Seq[Value] =
              json.obj
                .get("results")
                .map(v => Try(v.arr.toSeq).getOrElse(Seq.empty))
                .getOrElse(Seq.empty)

            val matches =
              rawResults.take(count).map(locationSummary)

            toolSuccess(
              Obj(
                "query" -> city,
                "matches" -> Arr(matches*)
              )
            )

  private def getCurrentWeather(args: Obj): Value =
    val latEither = requiredDouble(args, "latitude")
    val lonEither = requiredDouble(args, "longitude")

    (latEither, lonEither) match
      case (Left(problem), _) =>
        toolError(problem)

      case (_, Left(problem)) =>
        toolError(problem)

      case (Right(latitude), Right(longitude)) =>
        if latitude < -90 || latitude > 90 then
          toolError("latitude must be between -90 and 90.")
        else if longitude < -180 || longitude > 180 then
          toolError("longitude must be between -180 and 180.")
        else
          val timezone =
            optionalString(args, "timezone").getOrElse("auto")

          val url =
            "https://api.open-meteo.com/v1/forecast" +
              s"?latitude=$latitude" +
              s"&longitude=$longitude" +
              "&current=temperature_2m,wind_speed_10m,weather_code" +
              s"&timezone=${urlEncode(timezone)}"

          httpGetJson(url) match
            case Left(problem) =>
              toolError(problem)

            case Right(json) =>
              val current =
                json.obj.get("current").getOrElse(Obj())

              val currentOut =
                Obj(
                  "time" -> jsonStr(current, "time"),
                  "temperature_2m" -> jsonNum(current, "temperature_2m"),
                  "wind_speed_10m" -> jsonNum(current, "wind_speed_10m"),
                  "weather_code" -> jsonNum(current, "weather_code")
                )

              toolSuccess(
                Obj(
                  "latitude" -> jsonNum(json, "latitude"),
                  "longitude" -> jsonNum(json, "longitude"),
                  "timezone" -> jsonStr(json, "timezone"),
                  "current" -> currentOut
                )
              )

  private def httpGetJson(url: String): Either[String, Value] =
    try
      val request =
        HttpRequest
          .newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(15))
          .header("User-Agent", "openmeteo-scala-mcp/0.1.0")
          .GET()
          .build()

      val response =
        httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )

      val status = response.statusCode()
      val body = response.body()

      if status >= 200 && status < 300 then
        Right(ujson.read(body))
      else
        Left(s"Upstream HTTP $status: ${body.take(500)}")

    catch
      case NonFatal(e) =>
        Left(s"Upstream call failed: ${e.getMessage}")

  private def toolSuccess(structured: Value): Value =
    Obj(
      "content" -> Arr(
        Obj(
          "type" -> "text",
          "text" -> ujson.write(structured)
        )
      ),
      "structuredContent" -> structured,
      "isError" -> false
    )

  private def toolError(message: String): Value =
    Obj(
      "content" -> Arr(
        Obj(
          "type" -> "text",
          "text" -> message
        )
      ),
      "isError" -> true
    )

  private def requiredString(args: Obj, field: String): Either[String, String] =
    args.obj.get(field) match
      case Some(Str(s)) if s.trim.nonEmpty =>
        Right(s.trim)
      case Some(Str(_)) =>
        Left(s"$field must not be empty.")
      case _ =>
        Left(s"Missing required string argument: $field.")

  private def requiredDouble(args: Obj, field: String): Either[String, Double] =
    args.obj.get(field) match
      case Some(Num(n)) =>
        Right(n)
      case Some(Str(s)) =>
        Try(s.toDouble).toOption.toRight(s"$field must be a number.")
      case _ =>
        Left(s"Missing required numeric argument: $field.")

  private def optionalString(args: Obj, field: String): Option[String] =
    args.obj.get(field).collect { case Str(s) if s.trim.nonEmpty => s.trim }

  private def optionalInt(args: Obj, field: String, default: Int): Int =
    args.obj.get(field) match
      case Some(Num(n)) => n.toInt
      case Some(Str(s)) => Try(s.toInt).getOrElse(default)
      case _            => default

  private def locationSummary(v: Value): Value =
    Obj(
      "name" -> jsonStr(v, "name"),
      "country" -> jsonStr(v, "country"),
      "admin1" -> jsonStr(v, "admin1"),
      "latitude" -> jsonNum(v, "latitude"),
      "longitude" -> jsonNum(v, "longitude"),
      "timezone" -> jsonStr(v, "timezone")
    )

  private def jsonStr(v: Value, field: String): Value =
    Try(v.obj.get(field)).toOption.flatten match
      case Some(Str(s)) => Str(s)
      case Some(Null)   => Null
      case None         => Null
      case Some(other)  => Str(other.toString)

  private def jsonNum(v: Value, field: String): Value =
    Try(v.obj.get(field)).toOption.flatten match
      case Some(Num(n)) => Num(n)
      case Some(Str(s)) =>
        Try(s.toDouble).toOption match
          case Some(n) => Num(n)
          case None    => Null
      case _ =>
        Null

  private def urlEncode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)