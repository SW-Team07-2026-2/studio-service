package com.indie.studio.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** AWS 서비스(Concert · Catalog · Member · Payment)와 GPU 서버 자리에 세우는 가짜 HTTP 서버 — 받은 요청을 기록합니다 */
public class StubServer {

    public record Received(String method, String path, String query, Map<String, String> headers, byte[] body) {
        public String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    }

    public record Reply(int status, String json) {}

    private static final Reply NOT_FOUND = new Reply(404, "{\"isSuccess\":false,\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없습니다\"}");

    private final HttpServer server;
    private final Map<String, Function<Received, Reply>> routes = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply fallback = NOT_FOUND;

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        routes.clear();
        received.clear();
        fallback = NOT_FOUND;
    }

    /** 성공 응답 — result 자리에 넣을 JSON */
    public void ok(String method, String path, String resultJson) {
        reply(method, path, 200, "{\"isSuccess\":true,\"code\":\"OK\",\"message\":\"성공\",\"result\":" + resultJson + "}");
    }

    public void reply(String method, String path, int status, String json) {
        routes.put(method + " " + path, r -> new Reply(status, json));
    }

    /** 등록하지 않은 경로에 돌려줄 응답 */
    public void fallbackOk(String resultJson) {
        fallback = new Reply(200, "{\"isSuccess\":true,\"code\":\"OK\",\"message\":\"성공\",\"result\":" + resultJson + "}");
    }

    public List<Received> received(String method, String path) {
        return received.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(), headers, exchange.getRequestBody().readAllBytes());
        received.add(request);
        Function<Received, Reply> route = routes.get(request.method() + " " + request.path());
        Reply reply = route == null ? fallback : route.apply(request);
        byte[] body = reply.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
