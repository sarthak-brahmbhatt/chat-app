package com.chatapp.chatservice.bot.clinical;

import com.chatapp.chatservice.bot.toolcalling.BotStreamListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

/** Calls the Python extractor and forwards its safe progress messages. */
@Component
public class ClinicalExtractorClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI extractionUri;
    private final Duration timeout;

    public ClinicalExtractorClient(
            ObjectMapper objectMapper,
            @Value("${clinical-extractor.base-url}") String baseUrl,
            @Value("${clinical-extractor.timeout-seconds}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.extractionUri = URI.create(baseUrl + "/extract/stream");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public String extract(String note, BotStreamListener listener) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("note", note));
            HttpRequest request = HttpRequest.newBuilder(extractionUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<Stream<String>> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new ClinicalExtractorException("Extractor returned HTTP " + response.statusCode());
            }

            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    JsonNode event = objectMapper.readTree(line);
                    String type = event.path("type").asText();
                    if ("status".equals(type)) {
                        listener.onStatus(event.path("text").asText());
                    } else if ("result".equals(type)) {
                        return objectMapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(event.path("data"));
                    } else if ("error".equals(type)) {
                        throw new ClinicalExtractorException(event.path("message").asText());
                    }
                }
            }
            throw new ClinicalExtractorException("Extractor stream ended without a result");
        } catch (IOException e) {
            throw new ClinicalExtractorException("Could not read the extractor response", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClinicalExtractorException("Extractor request was interrupted", e);
        }
    }
}
