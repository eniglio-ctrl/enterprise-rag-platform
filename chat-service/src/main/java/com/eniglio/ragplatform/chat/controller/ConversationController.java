package com.eniglio.ragplatform.chat.controller;

import com.eniglio.ragplatform.chat.dto.CreateConversationResponse;
import com.eniglio.ragplatform.chat.dto.MessageDto;
import com.eniglio.ragplatform.chat.dto.SendMessageRequest;
import com.eniglio.ragplatform.chat.dto.SendMessageResponse;
import com.eniglio.ragplatform.chat.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Conversations", description = "Multi-turn conversations with memory, delegating retrieval to rag-service")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Operation(summary = "Start a new conversation")
    @PostMapping("/api/v1/conversations")
    public ResponseEntity<CreateConversationResponse> create(
            @RequestHeader(value = "X-Tenant-Id", required = false, defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {
        String conversationId = conversationService.createConversation(tenantId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateConversationResponse(conversationId));
    }

    @Operation(summary = "Send a message in a conversation",
            description = "Retrieves relevant chunks via rag-service and generates a conversation-aware answer")
    @PostMapping("/api/v1/conversations/{id}/messages")
    public SendMessageResponse sendMessage(@PathVariable("id") String conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader(value = "X-Tenant-Id", required = false, defaultValue = "default") String tenantId) {
        return conversationService.sendMessage(conversationId, tenantId, request.message());
    }

    @Operation(summary = "List the messages in a conversation")
    @GetMapping("/api/v1/conversations/{id}/messages")
    public List<MessageDto> getMessages(@PathVariable("id") String conversationId,
            @RequestHeader(value = "X-Tenant-Id", required = false, defaultValue = "default") String tenantId) {
        return conversationService.getMessages(conversationId, tenantId);
    }
}
