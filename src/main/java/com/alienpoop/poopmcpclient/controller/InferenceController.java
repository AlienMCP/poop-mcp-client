package com.alienpoop.poopmcpclient.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.alienpoop.poopmcpclient.dto.AiMessageParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@Slf4j
public class InferenceController {

  @Autowired private VectorStore vectorStore;
  @Autowired private ToolCallbackProvider toolCallbackProvider;
  @Autowired private ChatModel chatModel;

  @Autowired private ObjectMapper objectMapper;

  @Value("${hyperAGI.api}")
  private String hyperAGIAPI;

  private static final String SYSTEM_PROMPT_TEMPLATE =
      """
            Below is the system prompt
            {customSystemPrompt}
            You are an intelligent assistant. If the user's query matches information in the provided knowledge base, you must respond strictly and exclusively using the information from the knowledge base, without additional reasoning, improvisation, or external information. For other queries requiring specific actions (e.g., querying product information, calculating prices), use the provided tools. Otherwise, provide a concise and accurate response based on the provided information.
            ---------------------
            The following content is the knowledge base. If the user's question is addressed here, your response must be derived solely from this content.
            {context}
            ---------------------
            The following is the chat history. Use it only for contextual understanding when the knowledge base does not address the query, and do not let it influence the response if the knowledge base is applicable.
            {chatHistory}
            ---------------------
            {userText}
            """;

  @PostMapping(value = "asyncChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> asyncChat(@RequestBody AiMessageParams messageParams) {
    try {

      List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

      if (Boolean.FALSE.equals(messageParams.getOnlyTool())) {
        String sessionId =
            messageParams.getSessionId() != null ? messageParams.getSessionId() : "default_session";
        String userId =
            messageParams.getAssistantId() != null
                ? messageParams.getAssistantId()
                : "default_user";
        String textContent =
            messageParams.getTextContent() != null ? messageParams.getTextContent() : "";
        String customSystemPrompt =
            messageParams.getContent() != null ? messageParams.getContent() : "";

        String userText = toPrompt(messageParams);

        String chatHistory = useChatHistory(sessionId, 30);

        String vectorContext =
            useVectorStore(messageParams.getEnableVectorStore(), userId, textContent);

        SystemPromptTemplate systemPromptTemplate =
            new SystemPromptTemplate(SYSTEM_PROMPT_TEMPLATE);
        Map<String, Object> systemPromptParams = new HashMap<>();
        systemPromptParams.put("context", vectorContext);
        systemPromptParams.put("chatHistory", chatHistory);
        systemPromptParams.put("customSystemPrompt", customSystemPrompt);
        systemPromptParams.put("userText", userText);
        Prompt systemPrompt = systemPromptTemplate.create(systemPromptParams);

        messages.add(systemPrompt.getInstructions().get(0));
      }

      OllamaOptions chatOptions = OllamaOptions.builder().build();

      chatOptions.setToolCallbacks(Arrays.asList(toolCallbackProvider.getToolCallbacks()));

      Prompt prompt = new Prompt(messages, chatOptions);

      return ChatClient.create(chatModel).prompt(prompt).stream()
          .chatResponse()
          .doOnNext(
              chatResponse -> {
                if (chatResponse.getResult().getOutput().getMetadata() != null
                    && chatResponse.getResult().getMetadata() != null) {
                  log.info(
                      "GenerationMetadata: {}", chatResponse.getResult().getOutput().getMetadata());
                } else {
                  log.warn("No GenerationMetadata available");
                }
              })
          .flatMap(
              chatResponse -> {
                try {
                  String responseContent =
                      chatResponse != null && chatResponse.getResult() != null
                          ? chatResponse.getResult().getOutput().getText()
                          : "";
                  if (responseContent.isEmpty()) {
                    log.warn("Empty chatResponse content");
                    return Flux.just(
                        ServerSentEvent.builder("{\"error\": \"Empty response\"}")
                            .event("message")
                            .build());
                  }

                  return Flux.fromStream(
                          responseContent.chars().mapToObj(ch -> String.valueOf((char) ch)))
                      .map(
                          charContent -> {
                            try {
                              Map<String, Object> response = new HashMap<>();
                              response.put("content", charContent);
                              response.put("metadata", chatResponse.getMetadata().getUsage());
                              String json = objectMapper.writeValueAsString(response);
                              return ServerSentEvent.builder(json).event("message").build();
                            } catch (Exception e) {
                              log.error("JSON serialization error for char: {}", e.getMessage(), e);
                              return ServerSentEvent.builder("{\"error\": \"Serialization error\"}")
                                  .event("message")
                                  .build();
                            }
                          })
                      .delayElements(Duration.ofMillis(50));
                } catch (Exception e) {
                  log.error("Processing error: {}", e.getMessage(), e);
                  return Flux.just(
                      ServerSentEvent.builder("{\"error\": \"Processing error\"}")
                          .event("message")
                          .build());
                }
              })
          .concatWith(Flux.just(ServerSentEvent.builder("[DONE]").event("message").build()))
          .doOnError(e -> log.error("ChatClient error: {}", e.getMessage(), e))
          .doOnComplete(() -> log.info("ChatResponse stream completed"));
    } catch (Exception e) {
      log.error("AsyncChat error: {}", e.getMessage(), e);
      return Flux.just(
          ServerSentEvent.builder("{\"error\": \"" + e.getMessage() + "\"}")
              .event("error")
              .build());
    }
  }

  @PostMapping(value = "syncChat", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> syncChat(@RequestBody AiMessageParams messageParams) {
    try {

      List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

      if (!messageParams.getOnlyTool()) {
        String sessionId =
            messageParams.getSessionId() != null ? messageParams.getSessionId() : "default_session";
        String userId =
            messageParams.getAssistantId() != null
                ? messageParams.getAssistantId()
                : "default_user";
        String textContent =
            messageParams.getTextContent() != null ? messageParams.getTextContent() : "";
        String customSystemPrompt =
            messageParams.getContent() != null ? messageParams.getContent() : "";

        String userText = toPrompt(messageParams);

        String chatHistory = useChatHistory(sessionId, 30);

        String vectorContext =
            useVectorStore(messageParams.getEnableVectorStore(), userId, textContent);

        SystemPromptTemplate systemPromptTemplate =
            new SystemPromptTemplate(SYSTEM_PROMPT_TEMPLATE);
        Map<String, Object> systemPromptParams = new HashMap<>();
        systemPromptParams.put("context", vectorContext);
        systemPromptParams.put("chatHistory", chatHistory);
        systemPromptParams.put("customSystemPrompt", customSystemPrompt);
        systemPromptParams.put("userText", userText);
        Prompt systemPrompt = systemPromptTemplate.create(systemPromptParams);

        messages.add(systemPrompt.getInstructions().get(0));
      }

      OllamaOptions chatOptions = OllamaOptions.builder().build();

      chatOptions.setToolCallbacks(Arrays.asList(toolCallbackProvider.getToolCallbacks()));

      Prompt prompt = new Prompt(messages, chatOptions);

      log.info("prompt.getContents():{}", prompt.getContents());

      ChatClient chatClient = ChatClient.builder(chatModel).build();
      ChatResponse chatResponse = chatClient.prompt(prompt).call().chatResponse();

      log.info("ChatResponse: {}", chatResponse);
      log.info(
          "ChatResponse Content: {}",
          chatResponse != null && chatResponse.getResult() != null
              ? chatResponse.getResult().getOutput().getText()
              : "null");

      if (chatResponse.getResult() != null
          && chatResponse.getResult().getOutput().getToolCalls() != null) {
        log.info("Tool Calls: {}", chatResponse.getResult().getOutput().getToolCalls());
      }

      String responseContent =
          chatResponse != null && chatResponse.getResult() != null
              ? chatResponse.getResult().getOutput().getText()
              : "";
      if (responseContent.isEmpty()) {
        log.warn("Empty chatResponse content");
        return ResponseEntity.ok("{\"error\": \"Empty response\"}");
      }
      Object metadata =
          chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null
              ? chatResponse.getMetadata().getUsage()
              : new HashMap<>();
      String metadataJson = objectMapper.writeValueAsString(metadata);
      log.info("Serialized Usage Metadata: {}", metadataJson);

      Map<String, Object> response = new HashMap<>();
      response.put("content", responseContent);
      response.put("metadata", metadata);
      String json = objectMapper.writeValueAsString(response);
      log.info("Response JSON: {}", json);

      return ResponseEntity.ok(json);
    } catch (Exception e) {
      log.error("SyncChat error: {}", e.getMessage(), e);
      return ResponseEntity.status(500).body("{\"error\": \"" + e.getMessage() + "\"}");
    }
  }

  public String toPrompt(AiMessageParams input) {
    return input.getTextContent();
  }

  public String useVectorStore(Boolean enableVectorStore, String assistantId, String userText) {
    if (!enableVectorStore) return "";

    FilterExpressionBuilder b = new FilterExpressionBuilder();
    Filter.Expression exp = b.eq("assistantId", assistantId).build();
    log.info("filterExpression: {}", exp);

    SearchRequest searchRequest =
        SearchRequest.builder().query(userText).filterExpression(exp).build();

    List<Document> documentList = vectorStore.similaritySearch(searchRequest);
    List<String> texts = documentList.stream().map(Document::getText).toList();
    log.info("useVectorStore: {}", String.join("\n", texts));

    return String.join("\n", texts);
  }

  public String useChatHistory(String sessionId, Integer pageSize) {

    String body =
        HttpRequest.get(hyperAGIAPI + "/mgn/aiMessage/list")
            .form("pageNo", "1")
            .form("pageSize", pageSize.toString())
            .form("aiSessionId", sessionId)
            .form("column", "created_time")
            .timeout(10000)
            .execute()
            .body();

    JSONObject result = new JSONObject(body);

    JSONArray records = result.getJSONObject("result").getJSONArray("records");

    Collections.reverse(records);

    List<String> chatMemoryList =
        records.stream()
            .map(
                record -> {
                  JSONObject i = (JSONObject) record;

                  return i.getStr("type") + ":" + i.getStr("textContent");
                })
            .toList();

    return String.join("\n", chatMemoryList);
  }
}
