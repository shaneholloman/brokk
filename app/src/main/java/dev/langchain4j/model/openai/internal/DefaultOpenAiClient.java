package dev.langchain4j.model.openai.internal;

import static dev.langchain4j.http.client.HttpMethod.POST;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static java.time.Duration.ofSeconds;

import java.util.HashMap;
import java.util.Map;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.log.LoggingHttpClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;

public class DefaultOpenAiClient extends OpenAiClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Map<String, String> defaultHeaders;

    public DefaultOpenAiClient(Builder builder) {

        HttpClientBuilder httpClientBuilder = JdkHttpClient.builder();

        HttpClient httpClient = httpClientBuilder
                .connectTimeout(getOrDefault(getOrDefault(builder.connectTimeout, httpClientBuilder.connectTimeout()), ofSeconds(15)))
                .readTimeout(getOrDefault(getOrDefault(builder.readTimeout, httpClientBuilder.readTimeout()), ofSeconds(60)))
                .build();

        if (builder.logRequests || builder.logResponses) {
            this.httpClient = new LoggingHttpClient(httpClient, builder.logRequests, builder.logResponses);
        } else {
            this.httpClient = httpClient;
        }

        this.baseUrl = ensureNotBlank(builder.baseUrl, "baseUrl");

        Map<String, String> defaultHeaders = new HashMap<>();
        if (builder.apiKey != null) {
            defaultHeaders.put("Authorization", "Bearer " + builder.apiKey);
        }
        if (builder.organizationId != null) {
            defaultHeaders.put("OpenAI-Organization", builder.organizationId);
        }
        if (builder.projectId != null) {
            defaultHeaders.put("OpenAI-Project", builder.projectId);
        }
        if (builder.userAgent != null) {
            defaultHeaders.put("User-Agent", builder.userAgent);
        }
        if (builder.customHeaders != null) {
            defaultHeaders.putAll(builder.customHeaders);
        }
        this.defaultHeaders = defaultHeaders;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends OpenAiClient.Builder<DefaultOpenAiClient, Builder> {

        public DefaultOpenAiClient build() {
            return new DefaultOpenAiClient(this);
        }
    }

    @Override
    public SyncOrAsyncOrStreaming<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {

        HttpRequest httpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "chat/completions")
                .addHeader("Content-Type", "application/json")
                .addHeaders(defaultHeaders)
                .body(Json.toJson(ChatCompletionRequest.builder().from(request).stream(false).build()))
                .build();

        HttpRequest streamingHttpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "chat/completions")
                .addHeader("Content-Type", "application/json")
                .addHeaders(defaultHeaders)
                .body(Json.toJson(ChatCompletionRequest.builder().from(request).stream(true).build()))
                .build();

        return new RequestExecutor<>(httpClient, httpRequest, streamingHttpRequest, ChatCompletionResponse.class);
    }
}
