package com.gokhandegerli.personalvaultai.dto;

public record FeedbackRequest(
        String question,
        String answer,
        boolean helpful,
        String correctedAnswer) {
}
