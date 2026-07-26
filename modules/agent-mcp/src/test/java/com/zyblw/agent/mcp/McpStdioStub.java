package com.zyblw.agent.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仅供 stdio transport 契约测试使用的无依赖 MCP 进程。
 *
 * 使用纯 JDK 实现是为了让测试启动一个真实 OS 子进程，而不是把同一 JVM 内的 Queue 误当成 stdio。
 * 它只识别测试所需的固定方法，不承担通用 JSON 解析职责。
 */
public final class McpStdioStub {
  private static final Pattern ID = Pattern.compile("\\\"id\\\":([0-9]+)");

  private McpStdioStub() {}

  /**
   * @param args 第一个参数可为 normal、malformed、oversized 或 exit
   */
  public static void main(String[] args) throws Exception {
    String mode = args.length == 0 ? "normal" : args[0];
    BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    PrintWriter error = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    boolean cancelled = false;
    error.println("sensitive-stderr-content-must-not-enter-client-errors");

    String line;
    while ((line = input.readLine()) != null) {
      if (mode.equals("malformed")) {
        output.println("not-json");
        mode = "normal";
        continue;
      }
      if (mode.equals("oversized")) {
        output.println("x".repeat(8192));
        mode = "normal";
        continue;
      }
      if (mode.equals("exit")) {
        System.exit(7);
      }

      if (line.contains("notifications/cancelled")) {
        cancelled = true;
        continue;
      }
      if (line.contains("notifications/initialized")) {
        continue;
      }

      long id = id(line);
      if (line.contains("\"method\":\"initialize\"")) {
        output.println(response(id,
            "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{},\"resources\":{},\"prompts\":{}}," +
            "\"serverInfo\":{\"name\":\"stdio-stub\",\"version\":\"1.0.0\"}}"));
      } else if (line.contains("\"method\":\"tools/list\"")) {
        output.println(response(id,
            "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}]}"));
      } else if (line.contains("\"method\":\"tools/call\"")) {
        output.println(response(id,
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"structuredContent\":{\"ok\":true}}"));
      } else if (line.contains("\"method\":\"slow\"")) {
        // 故意不响应；客户端必须通过 timeout/interrupt 发出 notifications/cancelled。
      } else if (line.contains("\"method\":\"cancellation/status\"")) {
        output.println(response(id, "{\"cancelled\":" + cancelled + "}"));
      } else {
        output.println("{\"jsonrpc\":\"2.0\",\"id\":" + id +
            ",\"error\":{\"code\":-32601,\"message\":\"unknown method\"}}");
      }
    }
  }

  /** 提取当前客户端使用的数字 request id。 */
  private static long id(String line) {
    Matcher matcher = ID.matcher(line);
    if (!matcher.find()) throw new IllegalArgumentException("request id missing");
    return Long.parseLong(matcher.group(1));
  }

  /** 构造成功 JSON-RPC response。 */
  private static String response(long id, String result) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
  }
}
