package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public class HubClient {

    private static final String HUB_BASE = "http://localhost:7051";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public Optional<Hub> fetchHub(String hubId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(HUB_BASE + "/hubs/" + hubId))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("hub-service returned HTTP " + response.statusCode());
        }
        return Optional.of(mapper.readValue(response.body(), Hub.class));
    }
}