package com.gokhandegerli.personalvaultai.dto;

import java.util.List;

public record StoredMessage(String role, String content, List<Source> sources) {

    public StoredMessage {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
