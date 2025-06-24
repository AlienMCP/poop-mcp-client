package com.alienpoop.poopmcpclient.controller;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/knowledge")
@Slf4j
public class KnowledgeBaseController {

  @Autowired private VectorStore vectorStore;

  // 请求体：上传文档
  @Data
  public static class UploadRequest {
    private String assistantId;
    private String fileURL;
  }

  @Data
  public static class QueryRequest {
    private String query;
    private String assistantId;
    private int topK = 5;
  }

  @PostMapping("/upload")
  public ResponseEntity<Map<String, Object>> uploadDocument(@RequestBody UploadRequest request) {
    try {

      log.info("request:{}", request);

      FilterExpressionBuilder b = new FilterExpressionBuilder();

      Expression exp = b.eq("assistantId", request.getAssistantId()).build();

      log.info("filterExpression: {}", exp);

      SearchRequest searchRequest =
          SearchRequest.builder().topK(1000).filterExpression(exp).build();

      List<Document> documentList = vectorStore.similaritySearch(searchRequest);

      List<String> deleteIds = documentList.stream().map(Document::getId).toList();

      if (!deleteIds.isEmpty()) {
        vectorStore.delete(deleteIds);
      }

      List<Document> allSplitDocuments = new ArrayList<>();

      // Get the collection of file URLs
      String[] fileURLs = request.getFileURL().split(",");
      for (String fileURL : fileURLs) {

        if (StrUtil.isBlank(fileURL)) {
          continue;
        }

        // Create a TikaDocumentReader for the current file URL
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(fileURL);

        // Read and split satisfacer document
        List<Document> splitDocuments =
            new TokenTextSplitter(200, 200, 5, 10000, true).apply(tikaDocumentReader.read());

        // Add split documents to the overall list
        allSplitDocuments.addAll(splitDocuments);
      }

      for (Document doc : allSplitDocuments) {
        doc.getMetadata().put("assistantId", request.getAssistantId());
      }

      log.info(
          "allSplitDocuments: {}",
          StrUtil.join(
              "\n",
              allSplitDocuments.stream().map(Document::getText).collect(Collectors.toList())));


      if(!allSplitDocuments.isEmpty()){

        vectorStore.add(allSplitDocuments);
      }


      log.info("Uploaded document for assistantId: {}", request.getAssistantId());

      log.info("allSplitDocuments: {}", allSplitDocuments.size());

      return ResponseEntity.ok(
          Map.of("status", "success", "assistantId", request.getAssistantId()));
    } catch (Exception e) {
      log.error("Error uploading document: {}", e.getMessage(), e);
      return ResponseEntity.status(500)
          .body(Map.of("error", "Failed to upload document: " + e.getMessage()));
    }
  }

  @PostMapping("/delete")
  public ResponseEntity<Map<String, Object>> deleteDocument(@RequestParam String assistantId) {
    try {

      FilterExpressionBuilder b = new FilterExpressionBuilder();

      Expression exp = b.eq("assistantId", assistantId).build();

      log.info("filterExpression: {}", exp);

      SearchRequest searchRequest =
          SearchRequest.builder().topK(1000).filterExpression(exp).build();

      List<Document> documentList = vectorStore.similaritySearch(searchRequest);

      List<String> deleteIds = documentList.stream().map(Document::getId).toList();

      vectorStore.delete(deleteIds);

      return ResponseEntity.ok(
          Map.of("status", "success", "message", "Documents deleted successfully"));
    } catch (Exception e) {
      log.error("Error deleting document: {}", e.getMessage(), e);
      return ResponseEntity.status(500)
          .body(Map.of("error", "Failed to delete document: " + e.getMessage()));
    }
  }

  @PostMapping("/query")
  public ResponseEntity<Map<String, Object>> queryDocuments(
      @RequestBody KnowledgeBaseController.QueryRequest request) {
    try {

      SearchRequest.Builder builder =
          SearchRequest.builder().similarityThreshold(0.5).topK(request.getTopK());

      if (StrUtil.isNotBlank(request.getQuery())) {

        builder.query(request.getQuery());
        log.info(
            "Executing query search: {} with assistantId: {}",
            request.getQuery(),
            request.getAssistantId());
      } else {

        if (StrUtil.isNotBlank(request.getAssistantId())) {

          log.info(
              "Executing assistantId-based search for assistantId: {}", request.getAssistantId());
        } else {
          log.info("No query or assistantId provided, returning empty result");
          return ResponseEntity.ok(Map.of("status", "success", "results", Collections.emptyList()));
        }
      }

      // 添加 assistantId 过滤（如果提供）
      if (StrUtil.isNotBlank(request.getAssistantId())) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var exp = b.eq("assistantId", request.getAssistantId()).build();
        builder.filterExpression(exp);
      }

      SearchRequest searchRequest = builder.build();

      log.info("searchRequest: {}", searchRequest);

      List<Document> documents = vectorStore.similaritySearch(searchRequest);

      log.info(
          "Found {} documents for query: {} and assistantId: {}",
          documents.size(),
          request.getQuery(),
          request.getAssistantId());

      List<Map<String, Object>> results =
          documents.stream()
              .map(
                  doc ->
                      Map.of(
                          "id", doc.getId(),
                          "content", doc.getText(),
                          "metadata", doc.getMetadata()))
              .collect(Collectors.toList());

      return ResponseEntity.ok(Map.of("status", "success", "results", results));
    } catch (Exception e) {
      log.error("Error querying documents: {}", e.getMessage(), e);
      return ResponseEntity.status(500)
          .body(Map.of("error", "Failed to query documents: " + e.getMessage()));
    }
  }
}
