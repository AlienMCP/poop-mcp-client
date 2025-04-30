package com.alienpoop.poopmcpclient.dto;

import lombok.Data;

@Data
public class AiMessageParams {

  private Boolean enableVectorStore = false;
  private Boolean enableAgent = false;
  private String userId;
  private String content;
  private String textContent;
  private String sessionId;
  private String assistantId;
}
