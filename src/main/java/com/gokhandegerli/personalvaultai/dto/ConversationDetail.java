package com.gokhandegerli.personalvaultai.dto;

import java.util.List;

public record ConversationDetail(String id, String title, long createdAt, long updatedAt,
                                 List<StoredMessage> messages) {
}
