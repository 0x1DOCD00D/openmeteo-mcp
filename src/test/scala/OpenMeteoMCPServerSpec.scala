/*
 * ================================================================================
 *   Lone Star Consulting, Inc. / Mark Grechanik
 *   File: OpenMeteoMCPServerSpec.scala
 *   Project: openmeteo-mcp
 *   Module: openmeteo-mcp.test
 *   Created: 24 May 2026 17:02
 *   Last Modified: 24 May 2026 17:02
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

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.Assertion

import ujson.*

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.util.control.NonFatal

final class OpenMeteoMcpServerSpec extends AnyFunSuite with Matchers:

  private final class McpServerProcess private (
                                                 process: Process,
                                                 stdin: BufferedWriter,
                                                 lines: LinkedBlockingQueue[String]
                                               ):

    def send(json: Value): Unit =
      stdin.write(ujson.write(json))
      stdin.newLine()
      stdin.flush()

    def readJson(timeoutSeconds: Long = 5): Value =
      val line = lines.poll(timeoutSeconds, TimeUnit.SECONDS)
      if line == null then
        fail(s"Timed out waiting for JSON-RPC response after $timeoutSeconds seconds")
      else
        ujson.read(line)

    def close(): Unit =
      try stdin.close()
      catch case NonFatal(_) => ()

      process.destroy()

      if !process.waitFor(1, TimeUnit.SECONDS) then
        process.destroyForcibly()
        process.waitFor(1, TimeUnit.SECONDS)

  private object McpServerProcess:

    def start(): McpServerProcess =
      val classpath = System.getProperty("java.class.path")

      val process =
        ProcessBuilder("java", "-cp", classpath, "OpenMeteoMcpServer")
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start()

      val stdin =
        BufferedWriter(
          OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)
        )

      val stdout =
        BufferedReader(
          InputStreamReader(process.getInputStream, StandardCharsets.UTF_8)
        )

      val lines = LinkedBlockingQueue[String]()

      val readerThread =
        Thread(
          () =>
            try
              var line = stdout.readLine()
              while line != null do
                lines.offer(line)
                line = stdout.readLine()
            catch case NonFatal(_) => ()
          ,
          "openmeteo-mcp-test-stdout-reader"
        )

      readerThread.setDaemon(true)
      readerThread.start()

      McpServerProcess(process, stdin, lines)

  private def withServer(body: McpServerProcess => Assertion): Assertion =
    val server = McpServerProcess.start()
    try body(server)
    finally server.close()

  private def initializeRequest(id: Int): Value =
    Obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "method" -> "initialize",
      "params" -> Obj(
        "protocolVersion" -> "2025-11-25",
        "capabilities" -> Obj(),
        "clientInfo" -> Obj(
          "name" -> "scalatest-mcp-client",
          "version" -> "0.1.0"
        )
      )
    )

  private def initializedNotification: Value =
    Obj(
      "jsonrpc" -> "2.0",
      "method" -> "notifications/initialized"
    )

  private def toolsListRequest(id: Int): Value =
    Obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "method" -> "tools/list",
      "params" -> Obj()
    )

  private def toolCallRequest(id: Int, name: String, args: Obj): Value =
    Obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "method" -> "tools/call",
      "params" -> Obj(
        "name" -> name,
        "arguments" -> args
      )
    )

  private def initialize(server: McpServerProcess): Value =
    server.send(initializeRequest(1))
    val response = server.readJson()
    response("jsonrpc").str shouldBe "2.0"
    response("id").num.toInt shouldBe 1
    server.send(initializedNotification)
    response

  test("initialize advertises MCP tool capability and server metadata") {
    withServer { server =>
      server.send(initializeRequest(1))

      val response = server.readJson()
      val result = response("result")

      response("jsonrpc").str shouldBe "2.0"
      response("id").num.toInt shouldBe 1

      result("protocolVersion").str shouldBe "2025-11-25"
      result("capabilities")("tools")("listChanged").bool shouldBe false
      result("serverInfo")("name").str shouldBe "openmeteo-scala-mcp"
      result("serverInfo")("title").str should include("Open-Meteo")
      result("instructions").str should include("geocode_city")
      result("instructions").str should include("get_current_weather")
    }
  }

  test("tools/list exposes geocode_city and get_current_weather with useful schemas") {
    withServer { server =>
      initialize(server)

      server.send(toolsListRequest(2))
      val response = server.readJson()
      response("id").num.toInt shouldBe 2

      val tools = response("result")("tools").arr.toSeq
      val byName = tools.map(tool => tool("name").str -> tool).toMap

      byName.keySet should contain allOf ("geocode_city", "get_current_weather")

      val geocode = byName("geocode_city")
      geocode("description").str should include("coordinates")
      geocode("inputSchema")("required").arr.map(_.str).toSeq should contain("city")
      geocode("inputSchema")("properties")("city")("type").str shouldBe "string"

      val weather = byName("get_current_weather")
      weather("description").str should include("temperature")
      weather("inputSchema")("required").arr.map(_.str).toSeq should contain allOf ("latitude", "longitude")
      weather("inputSchema")("properties")("latitude")("type").str shouldBe "number"
      weather("inputSchema")("properties")("longitude")("type").str shouldBe "number"
    }
  }

  test("notifications/initialized does not produce a JSON-RPC response") {
    withServer { server =>
      server.send(initializeRequest(1))
      val initResponse = server.readJson()
      initResponse("id").num.toInt shouldBe 1

      server.send(initializedNotification)
      server.send(toolsListRequest(2))

      val nextResponse = server.readJson()
      nextResponse("id").num.toInt shouldBe 2
      nextResponse("result")("tools").arr.nonEmpty shouldBe true
    }
  }

  test("geocode_city rejects missing required city without calling the upstream API") {
    withServer { server =>
      initialize(server)

      server.send(toolCallRequest(3, "geocode_city", Obj("count" -> 3)))
      val response = server.readJson()
      val result = response("result")

      response("id").num.toInt shouldBe 3
      result("isError").bool shouldBe true
      result("content")(0)("text").str should include("Missing required string argument: city")
    }
  }

  test("get_current_weather rejects invalid latitude without calling the upstream API") {
    withServer { server =>
      initialize(server)

      server.send(
        toolCallRequest(
          4,
          "get_current_weather",
          Obj(
            "latitude" -> 100,
            "longitude" -> 0,
            "timezone" -> "auto"
          )
        )
      )

      val response = server.readJson()
      val result = response("result")

      response("id").num.toInt shouldBe 4
      result("isError").bool shouldBe true
      result("content")(0)("text").str should include("latitude must be between -90 and 90")
    }
  }

  test("tools/call reports unknown tool names as tool-level errors") {
    withServer { server =>
      initialize(server)

      server.send(toolCallRequest(5, "does_not_exist", Obj()))
      val response = server.readJson()
      val result = response("result")

      response("id").num.toInt shouldBe 5
      result("isError").bool shouldBe true
      result("content")(0)("text").str should include("Unknown tool: does_not_exist")
    }
  }

  test("optional network integration: geocode Chicago and then get current weather") {
    if sys.env.get("RUN_OPENMETEO_NETWORK_TESTS").contains("true") then
      withServer { server =>
        initialize(server)

        server.send(
          toolCallRequest(
            6,
            "geocode_city",
            Obj(
              "city" -> "Chicago",
              "count" -> 1
            )
          )
        )

        val geocodeResponse = server.readJson(timeoutSeconds = 15)
        val geocodeResult = geocodeResponse("result")

        geocodeResult("isError").bool shouldBe false

        val firstMatch =
          geocodeResult("structuredContent")("matches").arr.head

        firstMatch("latitude").num should be >= -90.0
        firstMatch("latitude").num should be <= 90.0
        firstMatch("longitude").num should be >= -180.0
        firstMatch("longitude").num should be <= 180.0

        server.send(
          toolCallRequest(
            7,
            "get_current_weather",
            Obj(
              "latitude" -> firstMatch("latitude").num,
              "longitude" -> firstMatch("longitude").num,
              "timezone" -> firstMatch("timezone").str
            )
          )
        )

        val weatherResponse = server.readJson(timeoutSeconds = 15)
        val weatherResult = weatherResponse("result")

        weatherResult("isError").bool shouldBe false
        weatherResult("structuredContent")("current").obj.keySet should contain allOf (
          "time",
          "temperature_2m",
          "wind_speed_10m",
          "weather_code"
        )
      }
    else
      cancel("Set RUN_OPENMETEO_NETWORK_TESTS=true to run live Open-Meteo integration test.")
  }
