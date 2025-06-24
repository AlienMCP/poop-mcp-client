package com.alienpoop.poopmcpclient.controller;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.alienpoop.poopmcpclient.dto.AiMessageParams;
import com.alienpoop.poopmcpclient.service.ToolCallbackService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@Slf4j
@EnableScheduling
public class InferenceController {

  @Autowired private VectorStore vectorStore;
  @Autowired private ToolCallbackProvider toolCallbackProvider;
  @Autowired private ChatModel chatModel;
  @Autowired private ObjectMapper objectMapper;

  @Autowired private ApplicationContext applicationContext;

  @Autowired private ToolCallbackService toolCallbackService;

  @Value("${spring.ai.ollama.chat.model}")
  private String model;

  @Value("${hyperAGI.api}")
  private String hyperAGIAPI;

  // Request counter metrics
  private final LongAdder requestCounter = new LongAdder();
  private final AtomicLong pendingRequests = new AtomicLong(0);
  private final LongAdder totalRequestsLastPeriod = new LongAdder();

  @PostMapping(value = "asyncChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> asyncChat(
      @RequestBody AiMessageParams messageParams, HttpServletRequest request) {

    // 验证输入参数
    if (messageParams == null) {
      log.error("AiMessageParams is null");
      return Flux.just(
          ServerSentEvent.builder("{\"error\": \"Invalid input: AiMessageParams is null\"}")
              .event("error")
              .build());
    }

    // 禁用 cogito:32b 的工具调用以确保兼容性
    if (model.equals("deepseek-r1:32b")) {
      messageParams.setEnableTool(false);
      messageParams.setOnlyTool(false);
    }

    StopWatch watch = new StopWatch();
    watch.start("asyncChat:" + getClientIP(request));

    requestCounter.increment();
    pendingRequests.incrementAndGet();
    totalRequestsLastPeriod.increment();

    try {
      log.info("Request IP: {}", getClientIP(request));
      log.info("Request textContent: {}", messageParams.getTextContent());

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
            new SystemPromptTemplate(getSystemPromptTemplate());
        Map<String, Object> systemPromptParams = new HashMap<>();
        systemPromptParams.put("context", vectorContext);
        systemPromptParams.put("chatHistory", chatHistory);
        systemPromptParams.put("customSystemPrompt", customSystemPrompt);
        systemPromptParams.put("userText", userText);
        Prompt systemPrompt = systemPromptTemplate.create(systemPromptParams);

        messages.add(systemPrompt.getInstructions().get(0));
      } else {
        String userText = toPrompt(messageParams);
        messages.add(new org.springframework.ai.chat.messages.UserMessage(userText));
      }

      OllamaOptions chatOptions = OllamaOptions.builder().build();
      if (messageParams.getEnableTool() || messageParams.getOnlyTool()) {
        chatOptions.setToolCallbacks(toolCallbackService.getFunctionCallbackList());
      }

      Prompt prompt = new Prompt(messages, chatOptions);

      // 使用同步调用获取完整响应
      ChatClient chatClient = ChatClient.builder(chatModel).build();
      ChatResponse chatResponse = chatClient.prompt(prompt).call().chatResponse();

      // 检查响应是否有效
      String content =
          chatResponse != null
                  && chatResponse.getResult() != null
                  && chatResponse.getResult().getOutput() != null
              ? chatResponse.getResult().getOutput().getText()
              : "";
      if (content.isEmpty()) {
        log.warn("Empty chatResponse content");
        return Flux.just(
            ServerSentEvent.builder("{\"error\": \"No content received\"}").event("error").build());
      }

      // 模拟流式输出
      Flux<ServerSentEvent<String>> contentFlux =
          Flux.fromStream(content.chars().mapToObj(ch -> String.valueOf((char) ch)))
              .map(
                  charContent -> {
                    try {
                      Map<String, Object> response = new HashMap<>();
                      response.put("content", charContent);
                      String json = objectMapper.writeValueAsString(response);
                      log.debug("Sending content event: {}", json);
                      return ServerSentEvent.builder(json).event("message").build();
                    } catch (JsonProcessingException e) {
                      log.error("JSON serialization error: {}", e.getMessage(), e);
                      return ServerSentEvent.builder("{\"error\": \"Serialization error\"}")
                          .event("error")
                          .build();
                    }
                  })
              .delayElements(Duration.ofMillis(50)); // 模拟流式输出的延迟

      // 处理元数据
      Object metadata =
          chatResponse.getMetadata() != null ? chatResponse.getMetadata().getUsage() : null;
      Flux<ServerSentEvent<String>> metadataFlux =
          Flux.just(metadata)
              .map(
                  meta -> {
                    try {
                      Map<String, Object> metadataResponse = new HashMap<>();
                      metadataResponse.put(
                          "metadata", meta != null ? meta : "No metadata available");
                      String json = objectMapper.writeValueAsString(metadataResponse);
                      log.debug("Sending metadata event: {}", json);
                      return ServerSentEvent.builder(json).event("metadata").build();
                    } catch (JsonProcessingException e) {
                      log.error("JSON serialization error for metadata: {}", e.getMessage(), e);
                      return ServerSentEvent.builder(
                              "{\"error\": \"Metadata serialization error\"}")
                          .event("error")
                          .build();
                    }
                  });

      return contentFlux
          .concatWith(metadataFlux)
          .doOnComplete(
              () -> {
                watch.stop();
                log.info(watch.prettyPrint(TimeUnit.SECONDS));
                pendingRequests.decrementAndGet();
              });

    } catch (Exception e) {
      log.error("AsyncChat error: {}", e.getMessage(), e);
      pendingRequests.decrementAndGet();
      if (e.getCause() instanceof InterruptedException) {
        initiateShutdown();
      }
      return Flux.just(
          ServerSentEvent.builder("{\"error\": \"" + e.getMessage() + "\"}")
              .event("error")
              .build());
    }
  }

  //  @PostMapping(value = "asyncChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  //  public Flux<ServerSentEvent<String>> asyncChat(
  //      @RequestBody AiMessageParams messageParams, HttpServletRequest request) {
  //
  //    if (model.equals("deepseek-r1:32b")) {
  //      messageParams.setEnableTool(false);
  //      messageParams.setOnlyTool(false);
  //    }
  //
  //    StopWatch watch = new StopWatch();
  //
  //    watch.start("asyncChat:" + getClientIP(request));
  //
  //    requestCounter.increment();
  //    pendingRequests.incrementAndGet();
  //    totalRequestsLastPeriod.increment();
  //
  //    try {
  //      log.info("Request IP: {}", getClientIP(request));
  //
  //      log.info("Request textContent: {}", messageParams.getTextContent());
  //
  //      List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
  //
  //      if (Boolean.FALSE.equals(messageParams.getOnlyTool())) {
  //        String sessionId =
  //            messageParams.getSessionId() != null ? messageParams.getSessionId() :
  // "default_session";
  //        String userId =
  //            messageParams.getAssistantId() != null
  //                ? messageParams.getAssistantId()
  //                : "default_user";
  //        String textContent =
  //            messageParams.getTextContent() != null ? messageParams.getTextContent() : "";
  //        String customSystemPrompt =
  //            messageParams.getContent() != null ? messageParams.getContent() : "";
  //
  //        String userText = toPrompt(messageParams);
  //        String chatHistory = useChatHistory(sessionId, 30);
  //        String vectorContext =
  //            useVectorStore(messageParams.getEnableVectorStore(), userId, textContent);
  //
  //        SystemPromptTemplate systemPromptTemplate =
  //            new SystemPromptTemplate(getSystemPromptTemplate());
  //        Map<String, Object> systemPromptParams = new HashMap<>();
  //        systemPromptParams.put("context", vectorContext);
  //        systemPromptParams.put("chatHistory", chatHistory);
  //        systemPromptParams.put("customSystemPrompt", customSystemPrompt);
  //        systemPromptParams.put("userText", userText);
  //        Prompt systemPrompt = systemPromptTemplate.create(systemPromptParams);
  //
  //        messages.add(systemPrompt.getInstructions().get(0));
  //      } else {
  //        String userText = toPrompt(messageParams);
  //        messages.add(new org.springframework.ai.chat.messages.UserMessage(userText));
  //      }
  //
  //      OllamaOptions chatOptions = OllamaOptions.builder().build();
  //
  //      if (messageParams.getEnableTool() || messageParams.getOnlyTool()) {
  //        chatOptions.setToolCallbacks(toolCallbackService.getFunctionCallbackList());
  //      }
  //
  //      Prompt prompt = new Prompt(messages, chatOptions);
  //
  //      return ChatClient.create(chatModel).prompt(prompt).stream()
  //          .chatResponse()
  //          .doOnNext(chatResponse -> {})
  //          .collectList()
  //          .flatMapMany(
  //              chatResponses -> {
  //                try {
  //                  StringBuilder fullContent = new StringBuilder();
  //                  for (ChatResponse chatResponse : chatResponses) {
  //                    String content =
  //                        chatResponse != null
  //                                && chatResponse.getResult() != null
  //                                && chatResponse.getResult().getOutput() != null
  //                            ? chatResponse.getResult().getOutput().getText()
  //                            : "";
  //                    if (!fullContent.toString().endsWith(content)) {
  //                      fullContent.append(content);
  //                    }
  //                  }
  //                  String finalContent = fullContent.toString();
  //
  //                  if (finalContent.isEmpty()) {
  //                    return Flux.just(
  //                        ServerSentEvent.builder("{\"error\": \"No content received\"}")
  //                            .event("error")
  //                            .build());
  //                  }
  //
  //                  Flux<ServerSentEvent<String>> contentFlux =
  //                      Flux.fromStream(
  //                              finalContent.chars().mapToObj(ch -> String.valueOf((char) ch)))
  //                          .map(
  //                              charContent -> {
  //                                try {
  //                                  Map<String, Object> response = new HashMap<>();
  //                                  response.put("content", charContent);
  //                                  String json = objectMapper.writeValueAsString(response);
  //                                  log.debug("Sending content event: {}", json);
  //                                  return ServerSentEvent.builder(json).event("message").build();
  //                                } catch (JsonProcessingException e) {
  //                                  log.error("JSON serialization error: {}", e.getMessage(), e);
  //                                  return ServerSentEvent.builder(
  //                                          "{\"error\": \"Serialization error\"}")
  //                                      .event("error")
  //                                      .build();
  //                                }
  //                              })
  //                          .delayElements(Duration.ofMillis(50));
  //
  //                  ChatResponse lastResponse =
  //                      chatResponses.isEmpty() ? null : chatResponses.get(chatResponses.size() -
  // 1);
  //                  Object metadata = null;
  //                  try {
  //                    metadata =
  //                        lastResponse != null && lastResponse.getMetadata() != null
  //                            ? lastResponse.getMetadata().getUsage()
  //                            : null;
  //                  } catch (Exception e) {
  //                    log.error("Error accessing metadata: {}", e.getMessage(), e);
  //                  }
  //
  //                  Flux<ServerSentEvent<String>> metadataFlux =
  //                      Flux.just(metadata)
  //                          .map(
  //                              meta -> {
  //                                try {
  //                                  Map<String, Object> metadataResponse = new HashMap<>();
  //                                  if (meta != null) {
  //                                    metadataResponse.put("metadata", meta);
  //                                  } else {
  //                                    metadataResponse.put("metadata", "No metadata available");
  //                                  }
  //                                  String json =
  // objectMapper.writeValueAsString(metadataResponse);
  //                                  log.debug("Sending metadata event: {}", json);
  //                                  return
  // ServerSentEvent.builder(json).event("metadata").build();
  //                                } catch (JsonProcessingException e) {
  //                                  log.error(
  //                                      "JSON serialization error for metadata: {}",
  //                                      e.getMessage(),
  //                                      e);
  //                                  return ServerSentEvent.builder(
  //                                          "{\"error\": \"Metadata serialization error\"}")
  //                                      .event("error")
  //                                      .build();
  //                                }
  //                              });
  //
  //                  return contentFlux.concatWith(metadataFlux);
  //                } catch (Exception e) {
  //                  log.error("Processing error: {}", e.getMessage(), e);
  //                  return Flux.just(
  //                      ServerSentEvent.builder(
  //                              "{\"error\": \"Processing error: " + e.getMessage() + "\"}")
  //                          .event("error")
  //                          .build());
  //                }
  //              })
  //          .doOnError(e -> log.error("ChatClient error: {}", e.getMessage(), e))
  //          .doOnComplete(
  //              () -> {
  //                watch.stop();
  //                watch.prettyPrint();
  //
  //                log.info(watch.prettyPrint(TimeUnit.SECONDS));
  //
  //                pendingRequests.decrementAndGet();
  //              });
  //
  //    } catch (Exception e) {
  //      log.error("AsyncChat error: {}", e.getMessage(), e);
  //
  //      pendingRequests.decrementAndGet();
  //
  //      if (e.getCause() instanceof InterruptedException) {
  //        initiateShutdown();
  //      }
  //
  //      return Flux.just(
  //          ServerSentEvent.builder("{\"error\": \"" + e.getMessage() + "\"}")
  //              .event("error")
  //              .build());
  //    }
  //  }

  @PostMapping(value = "syncChat", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> syncChat(
      @RequestBody AiMessageParams messageParams, HttpServletRequest request) {
    Instant startTime = Instant.now();
    requestCounter.increment();
    pendingRequests.incrementAndGet();
    totalRequestsLastPeriod.increment();

    if (model.equals("deepseek-r1:32b")) {
      messageParams.setEnableTool(false);
      messageParams.setOnlyTool(false);
    }

    try {
      log.info("Request IP: {}", getClientIP(request));

      log.info("Request textContent: {}", messageParams.getTextContent());

      StopWatch watch = new StopWatch();

      watch.start("syncChat:" + getClientIP(request));

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
            new SystemPromptTemplate(getSystemPromptTemplate());
        Map<String, Object> systemPromptParams = new HashMap<>();
        systemPromptParams.put("context", vectorContext);
        systemPromptParams.put("chatHistory", chatHistory);
        systemPromptParams.put("customSystemPrompt", customSystemPrompt);
        systemPromptParams.put("userText", userText);
        Prompt systemPrompt = systemPromptTemplate.create(systemPromptParams);

        messages.add(systemPrompt.getInstructions().get(0));
      } else {
        String userText = toPrompt(messageParams);
        messages.add(new org.springframework.ai.chat.messages.UserMessage(userText));
      }

      OllamaOptions chatOptions = OllamaOptions.builder().build();

      if (messageParams.getEnableTool() || messageParams.getOnlyTool()) {
        chatOptions.setToolCallbacks(toolCallbackService.getFunctionCallbackList());
      }

      Prompt prompt = new Prompt(messages, chatOptions);

      ChatClient chatClient = ChatClient.builder(chatModel).build();
      ChatResponse chatResponse = chatClient.prompt(prompt).call().chatResponse();

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

      watch.stop();

      log.info(watch.prettyPrint(TimeUnit.SECONDS));

      pendingRequests.decrementAndGet();
      return ResponseEntity.ok(json);
    } catch (Exception e) {

      log.error("SyncChat error: {}", e.getMessage(), e);

      pendingRequests.decrementAndGet();

      return ResponseEntity.status(500).body("{\"error\": \"" + e.getMessage() + "\"}");
    }
  }

  @Scheduled(fixedRate = 60000) // Run every 60 seconds
  public void logRequestMetrics() {
    long requestsInPeriod = totalRequestsLastPeriod.sumThenReset();
    long currentPending = pendingRequests.get();
    long totalRequests = requestCounter.sum();

    log.info(
        "Request Metrics: Total Requests = {}, Requests Per Minute = {}, Pending Requests = {}",
        totalRequests,
        requestsInPeriod,
        currentPending);
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
        SearchRequest.builder()
            .similarityThreshold(0)
            .query(userText)
            .filterExpression(exp)
            .build();

    List<Document> documentList = vectorStore.similaritySearch(searchRequest);
    List<String> texts = documentList.stream().map(Document::getText).toList();

    log.info("texts: {}", StrUtil.join(",", texts));

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

    List<String> swappedChatMemoryList =
        IntStream.range(0, chatMemoryList.size() / 2)
            .mapToObj(
                i -> {
                  int index = i * 2;
                  return Arrays.asList(chatMemoryList.get(index + 1), chatMemoryList.get(index));
                })
            .flatMap(List::stream)
            .toList();

    return String.join("\n", swappedChatMemoryList);
  }

  private String getSystemPromptTemplate() {
    String body =
        HttpRequest.get(hyperAGIAPI + "/sys/dict/getDictText/sys_config/SYSTEM_PROMPT_TEMPLATE")
            .timeout(10000)
            .execute()
            .body();

    JSONObject result = new JSONObject(body);
    String systemPromptTemplate = result.getStr("result");

    return systemPromptTemplate;
  }

  private void initiateShutdown() {
    log.info("Initiating application shutdown due to InterruptedException");
    // 使用 Spring 的 ApplicationContext 关闭应用程序
    ConfigurableApplicationContext context = (ConfigurableApplicationContext) applicationContext;
    context.close();
    // 或者直接退出 JVM
    System.exit(1);
  }

  public static String getClientIP(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
      // X-Forwarded-For 可能包含多个 IP，第一个是客户端真实 IP
      int index = ip.indexOf(",");
      if (index != -1) {
        return ip.substring(0, index).trim();
      }
      return ip;
    }
    ip = request.getHeader("X-Real-IP");
    if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
      return ip;
    }
    return request.getRemoteAddr(); // 直接获取 IP，可能为代理服务器 IP
  }

  public static void main(String[] args) throws InterruptedException {

    StopWatch watch = new StopWatch();

    // 启动计时
    watch.start("Task 1");

    // 模拟一些耗时操作
    Thread.sleep(1000); // 暂停1秒

    // 停止计时
    watch.stop();

    // 打印结果
    log.info(watch.prettyPrint());
  }
}
