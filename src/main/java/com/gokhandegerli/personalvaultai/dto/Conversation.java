package com.gokhandegerli.personalvaultai.dto;

import java.util.ArrayList;
import java.util.List;

public class Conversation {

    private String id;
    private String title;
    private long createdAt;
    private long updatedAt;
    private List<StoredMessage> messages;

    public Conversation() {
    }

    public Conversation(String id, long now) {
        this.id = id;
        this.title = "";
        this.createdAt = now;
        this.updatedAt = now;
        this.messages = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<StoredMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<StoredMessage> messages) {
        this.messages = messages;
    }
}
