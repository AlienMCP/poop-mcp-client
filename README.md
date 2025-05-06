# Poop MCP Client

Poop MCP Client is a Spring Boot application that serves as a client for the Model Control Plane (MCP). It provides a RESTful API for AI inference and knowledge base management, leveraging Spring AI and Ollama for language model integration.

## Features

- **AI Chat Interface**: Supports both synchronous and asynchronous chat with AI models
- **Knowledge Base Management**: Upload, query, and delete documents in a vector store
- **Vector Search**: Semantic search capabilities using Neo4j as a vector database
- **Document Processing**: Process and split documents for efficient storage and retrieval
- **Environment Configuration**: Flexible configuration through environment variables

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Docker (for containerized deployment)
- Neo4j database
- Ollama server with required models

## Environment Variables

The application requires the following environment variables:

- `NEO4J_URI`: URI for the Neo4j database
- `OLLAMA_BASE_URL`: Base URL for the Ollama server
- `MCP_SERVER`: URL for the Model Control Plane server
- `HYPER_AGI_API`: URL for the HyperAGI API, used for retrieving chat history

## Installation

### Local Development

1. Clone the repository
2. Configure environment variables (create a `.env` file in the `src/main/resources` directory)
3. Build the project:
   ```bash
   mvn clean package
   ```
4. Run the application:
   ```bash
   java -jar target/poop-mcp-client-0.0.1-SNAPSHOT.jar
   ```

### Docker Deployment

1. Build the Docker image:
   ```bash
   docker build -t sputnik-mcp-client .
   ```
2. Run the container:
   ```bash
   docker run -p 8881:8881 --env-file .env sputnik-mcp-client
   ```

## API Documentation

### Inference API

#### Chat API (Asynchronous)

```
POST /chat/async
```

Request body:
```json
{
  "enableVectorStore": true,
  "onlyTool": false,
  "userId": "user123",
  "content": "Custom system prompt",
  "textContent": "User message",
  "sessionId": "session123",
  "assistantId": "assistant123"
}
```

Returns a Server-Sent Events (SSE) stream with the AI response.

#### Chat API (Synchronous)

```
POST /chat/sync
```

Request body: Same as async API

Returns a JSON response with the complete AI response.

### Knowledge Base API

#### Upload Document

```
POST /knowledge/upload
```

Request body:
```json
{
  "assistantId": "assistant123",
  "fileURL": "https://example.com/document.pdf"
}
```

#### Delete Document

```
POST /knowledge/delete?assistantId=assistant123
```

#### Query Knowledge Base

```
POST /knowledge/query
```

Request body:
```json
{
  "query": "search query",
  "assistantId": "assistant123",
  "topK": 5
}
```

## Configuration

The application is configured through `application.yaml`. Key configuration options include:

- Neo4j vector store settings
- Ollama model configuration
- MCP client settings
- Server port (default: 8881)

## Building from Source

```bash
mvn clean package
```

This will create a JAR file in the `target` directory.

## Customizing Chat History Retrieval

The application uses the `HYPER_AGI_API` environment variable to retrieve chat history from an external service. The chat history is used to provide context for AI responses.

### Current Implementation

The chat history is retrieved from `${HYPER_AGI_API}/mgn/aiMessage/list` with the following parameters:
- `pageNo`: Page number (default: 1)
- `pageSize`: Number of messages to retrieve
- `aiSessionId`: Session ID to retrieve messages for
- `column`: Sort column (default: "created_time")

### Modifying Chat History Retrieval

To modify how chat history is retrieved:

1. Locate the `useChatHistory` method in `InferenceController.java`
2. Modify the method to use a different API endpoint or data source
3. Ensure the method returns a string with the chat history in the format "type:textContent" with each message on a new line

Example of a custom implementation:

```java
public String useChatHistory(String sessionId, Integer pageSize) {
    // Custom implementation to retrieve chat history
    // Could use a database, file system, or different API

    // Example with a different API
    String body = HttpRequest.get("https://your-custom-api.com/chat-history")
        .form("sessionId", sessionId)
        .form("limit", pageSize.toString())
        .execute()
        .body();

    // Parse the response and format the chat history
    // ...

    return formattedChatHistory;
}
```


