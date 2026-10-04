package dev.recall;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Component
public class JsonHttp {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public JsonHttp(ObjectMapper mapper) { this.mapper = mapper; }
    public JsonNode post(String url, String key, Object body) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("上游服务返回 HTTP " + response.statusCode() + "，请检查服务端配置。");
            return mapper.readTree(response.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("请求已中断。"); }
        catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("暂时无法连接上游服务，请稍后重试。"); }
    }
}
