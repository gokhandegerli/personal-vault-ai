package com.gokhandegerli.personalvaultai.dto;

import java.util.List;

public record ChatResponse(String answer, List<Source> sources) {
}
