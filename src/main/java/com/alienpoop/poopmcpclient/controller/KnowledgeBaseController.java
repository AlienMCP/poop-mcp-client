package com.alienpoop.poopmcpclient.controller;

import cn.hutool.core.util.StrUtil;
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

  // 请求体：查询文档
  @Data
  public static class QueryRequest {
    private String query;
    private String assistantId;
    private int topK = 5; // 默认返回前 5 个结果
  }

  /** 上传文档到知识库 */
  @PostMapping("/upload")
  public ResponseEntity<Map<String, Object>> uploadDocument(@RequestBody UploadRequest request) {
    try {
      if (StrUtil.isBlank(request.getFileURL()) || StrUtil.isBlank(request.getAssistantId())) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "Content and assistantId are required"));
      }

      FilterExpressionBuilder b = new FilterExpressionBuilder();

      Expression exp = b.eq("assistantId", request.getAssistantId()).build();

      log.info("filterExpression: {}", exp);

      SearchRequest searchRequest =
          SearchRequest.builder().topK(1000).filterExpression(exp).build();

      List<Document> documentList = vectorStore.similaritySearch(searchRequest);

      List<String> deleteIds = documentList.stream().map(Document::getId).toList();

      // 先删除该用户之前的内容
      vectorStore.delete(deleteIds);

      TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(request.getFileURL());

      // 将文本内容划分成更小的块
      List<Document> splitDocuments =
          new TokenTextSplitter(200, 200, 5, 10000, true).apply(tikaDocumentReader.read());

      // 为每个文档添加用户ID
      for (Document doc : splitDocuments) {
        doc.getMetadata().put("assistantId", request.getAssistantId());
      }

      vectorStore.add(splitDocuments);

      log.info("Uploaded document for assistantId: {}", request.getAssistantId());

      return ResponseEntity.ok(
          Map.of("status", "success", "assistantId", request.getAssistantId()));
    } catch (Exception e) {
      log.error("Error uploading document: {}", e.getMessage(), e);
      return ResponseEntity.status(500)
          .body(Map.of("error", "Failed to upload document: " + e.getMessage()));
    }
  }

  /** 删除知识库中的文档 */
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

      return ResponseEntity.ok(
          Map.of("status", "success", "message", "Documents deleted successfully"));
    } catch (Exception e) {
      log.error("Error deleting document: {}", e.getMessage(), e);
      return ResponseEntity.status(500)
          .body(Map.of("error", "Failed to delete document: " + e.getMessage()));
    }
  }

  /** 查询知识库 */
  @PostMapping("/query")
  public ResponseEntity<Map<String, Object>> queryDocuments(
      @RequestBody KnowledgeBaseController.QueryRequest request) {
    try {

      // 构建搜索请求
      SearchRequest.Builder builder = SearchRequest.builder().topK(request.getTopK());

      // 处理查询字符串
      if (StrUtil.isNotBlank(request.getQuery())) {
        // query 不为空，追加检索
        builder.query(request.getQuery());
        log.info(
            "Executing query search: {} with assistantId: {}",
            request.getQuery(),
            request.getAssistantId());
      } else {
        // query 为空，基于 assistantId 检索（如果有）或返回空结果
        if (StrUtil.isNotBlank(request.getAssistantId())) {
          builder.query(""); // 空查询，仅使用过滤条件
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

      // 执行搜索
      List<Document> documents = vectorStore.similaritySearch(searchRequest);
      log.info(
          "Found {} documents for query: {} and assistantId: {}",
          documents.size(),
          request.getQuery(),
          request.getAssistantId());

      // 格式化响应
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
