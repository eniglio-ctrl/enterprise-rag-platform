package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.gateway.VisionGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ImageDescriptionServiceTest {

    @Mock
    private ChatModel chatModel;

    @Test
    void describesAnImageUsingTheVisionModel() {
        given(chatModel.call(any(Prompt.class))).willReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("A flowchart with three boxes labeled A, B and C.")))));

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        ImageDescriptionService service = new ImageDescriptionService(chatClient, new VisionGateway());

        String description = service.describe(new byte[]{1, 2, 3}, MimeTypeUtils.IMAGE_PNG);

        assertThat(description).contains("flowchart");
    }
}
