package com.eniglio.ragplatform.chat.dto;

import com.eniglio.ragplatform.common.web.Citation;

import java.util.List;

public record SendMessageResponse(String answer, List<Citation> citations) {
}
