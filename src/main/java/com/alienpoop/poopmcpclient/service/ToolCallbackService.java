package com.alienpoop.poopmcpclient.service;

import java.util.Arrays;
import java.util.List;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Component responsible for periodically checking the health of the ToolCallbackProvider by
 * invoking getToolCallbacks(). If an error occurs, it triggers an application shutdown.
 */
@Component
public class ToolCallbackService {

  @Autowired private ToolCallbackProvider toolCallbackProvider;

  private List<FunctionCallback> functionCallbackList;

  public List<FunctionCallback> getFunctionCallbackList() {
    if (functionCallbackList == null) {
      functionCallbackList = Arrays.asList(toolCallbackProvider.getToolCallbacks());
    }
    return functionCallbackList;
  }
}
