package com.alienpoop.poopmcpclient.service;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Component responsible for periodically checking the health of the ToolCallbackProvider by
 * invoking getToolCallbacks(). If an error occurs, it triggers an application shutdown.
 */
@Component
@Slf4j
public class ToolCallbackService {

  @Autowired private ToolCallbackProvider toolCallbackProvider;

  private List<FunctionCallback> functionCallbackList;

  public List<FunctionCallback> getFunctionCallbackList() {
    if (functionCallbackList == null) {
      functionCallbackList = Arrays.asList(toolCallbackProvider.getToolCallbacks());
    }
    return functionCallbackList;
  }

  @Scheduled(cron = "0 * * * * ?")
  @PostConstruct
  public void ping() {

    try {

      log.info("Pinging ToolCallbackProvider...");


      toolCallbackProvider.getToolCallbacks();
    } catch (Exception e) {

      log.error("Error while pinging ToolCallbackProvider: ", e);
      System.exit(500);
    }
  }
}
