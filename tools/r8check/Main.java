import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.*;
import java.util.List;

public class Main {
    public static void main(String[] a) throws Exception {
        MessageCreateParams p = MessageCreateParams.builder()
            .model("claude-opus-5-5").maxTokens(16000L).system("sys")
            .addTool(WebSearchTool20260209.builder().maxUses(5L).allowedDomains(List.of("ligonier.org")).build())
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessage("Q1").addAssistantMessage("A1").addUserMessage("Q2").build();
        String body = ObjectMappers.jsonMapper().writeValueAsString(p._body());
        System.out.println("BODY " + body);
        String resp = "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"x\",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1},\"content\":["
          + "{\"type\":\"server_tool_use\",\"id\":\"s\",\"name\":\"web_search\",\"input\":{\"query\":\"q\"}},"
          + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"s\",\"content\":[{\"type\":\"web_search_result\",\"url\":\"https://ligonier.org/a\",\"title\":\"T\",\"encrypted_content\":\"x\",\"page_age\":null}]},"
          + "{\"type\":\"text\",\"text\":\"Holy.\",\"citations\":[{\"type\":\"web_search_result_location\",\"url\":\"https://ligonier.org/a\",\"title\":\"T\",\"encrypted_index\":\"x\",\"cited_text\":\"c\"}]}]}";
        Message m = ObjectMappers.jsonMapper().readValue(resp, Message.class);
        for (ContentBlock b : m.content()) {
            b.webSearchToolResult().ifPresent(r -> System.out.println("RESULTS " + r.content().resultBlocks().map(List::size).orElse(-1)));
            b.text().ifPresent(t -> System.out.println("TEXT " + t.text() + " CIT " + t.citations().map(c -> c.get(0).webSearchResultLocation().map(w -> w.url()).orElse("?")).orElse("none")));
        }
        // Continuation of a paused turn sends the assistant message back.
        String body2 = ObjectMappers.jsonMapper().writeValueAsString(p.toBuilder().addMessage(m).build()._body());
        System.out.println("BODY2 " + body2);
        // The whole round trip through the client, against a local server.
        final String reply = resp;
        final String[] got = new String[2];
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            got[0] = new String(ex.getRequestBody().readAllBytes());
            got[1] = ex.getRequestHeaders().getFirst("anthropic-beta") + " " + ex.getRequestHeaders().getFirst("x-api-key");
            byte[] out = reply.getBytes();
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close();
        });
        srv.start();
        com.anthropic.client.AnthropicClient client = com.anthropic.client.okhttp.AnthropicOkHttpClient.builder()
            .apiKey("sk-test").baseUrl("http://127.0.0.1:" + srv.getAddress().getPort()).build();
        Message live = client.messages().create(p);
        System.out.println("SENT " + got[0]);
        System.out.println("HEADERS " + got[1]);
        System.out.println("LIVE " + live.content().size() + " " + live.stopReason().map(Object::toString).orElse("?"));
        srv.stop(0);
        System.exit(0);
    }
}
