package com.alienpoop.poopmcpclient.controller;

import cn.hutool.core.util.StrUtil;
import com.alienpoop.poopmcpclient.dto.AiMessageParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
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

  @Autowired private EmbeddingModel embeddingModel;

  private static final String PROMPT =
      """
      Below is the system prompt:
      {}
      ---------------------
      The following is additional knowledge from the knowledge base. If the knowledge base contains relevant content (non-empty and related to the query), prioritize it to generate the answer and do not call any functions:
      {}
      ---------------------
      The following is the chat history between us:
      {}
      """;

  @PostMapping(value = "asyncChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> asyncChat(@RequestBody AiMessageParams messageParams) {
    try {
      String chatMemoryStr = "";
      String knowledgeBaseStr = "";

      if (messageParams.getEnableVectorStore()) {
        knowledgeBaseStr =
            getKnowledgeBase(messageParams.getAssistantId(), messageParams.getTextContent());
        log.info("KnowledgeBase: {}", knowledgeBaseStr);
      }

      String content = messageParams.getContent() != null ? messageParams.getContent() : "";
      String textContent =
          messageParams.getTextContent() != null ? messageParams.getTextContent() : "";
      String prompt = StrUtil.format(PROMPT, content, knowledgeBaseStr, chatMemoryStr, textContent);
      log.info("Prompt: {}", prompt);
      log.info(
          "EnableVectorStore: {}, EnableAgent: {}",
          messageParams.getEnableVectorStore(),
          messageParams.getEnableAgent());

      return ChatClient.create(chatModel)
          .prompt(prompt)
          .tools(messageParams.getEnableAgent() ? toolCallbackProvider : null)
          .stream()
          .chatResponse()
          .doOnNext(
              chatResponse -> {
                // 记录 GenerationMetadata
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
                  // 获取 textContent
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

                  // 逐字符流式输出 textContent 和 metadata
                  return Flux.fromStream(
                          responseContent.chars().mapToObj(ch -> String.valueOf((char) ch)))
                      .map(
                          charContent -> {
                            try {
                              // 构建包含 content 和 metadata 的 JSON
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

  // 新增同步方法
  @PostMapping(value = "syncChat", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> syncChat(@RequestBody AiMessageParams messageParams) {
    try {
      String chatMemoryStr = "";
      String knowledgeBaseStr = "";

      if (messageParams.getEnableVectorStore()) {
        knowledgeBaseStr =
            getKnowledgeBase(messageParams.getAssistantId(), messageParams.getTextContent());
        log.info("KnowledgeBase: {}", knowledgeBaseStr);
      }

      String content = messageParams.getContent() != null ? messageParams.getContent() : "";
      String textContent =
          messageParams.getTextContent() != null ? messageParams.getTextContent() : "";
      String prompt = StrUtil.format(PROMPT, content, knowledgeBaseStr, chatMemoryStr, textContent);
      log.info("Prompt: {}", prompt);
      log.info(
          "EnableVectorStore: {}, EnableAgent: {}",
          messageParams.getEnableVectorStore(),
          messageParams.getEnableAgent());

      // 同步调用 ChatClient
      ChatResponse chatResponse =
          ChatClient.create(chatModel)
              .prompt(prompt)
              .tools(messageParams.getEnableAgent() ? toolCallbackProvider : null)
              .call()
              .chatResponse();

      log.info("ChatResponse: {}", chatResponse);
      log.info(
          "ChatResponse Content: {}",
          chatResponse != null && chatResponse.getResult() != null
              ? chatResponse.getResult().getOutput().getText()
              : "null");

      // 获取 textContent
      String responseContent =
          chatResponse != null && chatResponse.getResult() != null
              ? chatResponse.getResult().getOutput().getText()
              : "";
      if (responseContent.isEmpty()) {
        log.warn("Empty chatResponse content");
        return ResponseEntity.ok("{\"error\": \"Empty response\"}");
      }

      // 获取 Usage Metadata
      Object metadata =
          chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null
              ? chatResponse.getMetadata().getUsage()
              : new HashMap<>();
      String metadataJson = objectMapper.writeValueAsString(metadata);
      log.info("Serialized Usage Metadata: {}", metadataJson);

      // 构建响应 JSON
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

  //  private String chatMemoryStrList(String sessionId) {
  //
  //    List<Map<String, Object>> list =
  //        jdbcTemplate.queryForList(
  //            "select * from ai_message where ai_session_id = ? order by created_time desc limit
  // 30",
  //            sessionId);
  //
  //    Collections.reverse(list);
  //
  //    List<String> chatMemoryStrList =
  //        list.stream().map(i -> i.get("type") + ":" + i.get("text_content")).toList();
  //
  //    return StrUtil.join("\n", chatMemoryStrList);
  //  }

  private String getKnowledgeBase(String assistantId, String textContent) {

    FilterExpressionBuilder b = new FilterExpressionBuilder();
    Expression exp = b.in("assistantId", assistantId).build();

    log.info("filterExpression: {}", exp);

    SearchRequest searchRequest =
        SearchRequest.builder().filterExpression(exp).query(textContent).build();

    List<Document> documentList = vectorStore.similaritySearch(searchRequest);

    List<String> knowledgeBaseList = documentList.stream().map(Document::getText).toList();

    return StrUtil.join("\n", knowledgeBaseList);
  }
}
