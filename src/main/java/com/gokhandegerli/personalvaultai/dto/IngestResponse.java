package com.gokhandegerli.personalvaultai.dto;

import java.util.List;

public record IngestResponse(String storeType, int filesRead, int chunksWritten, List<String> skipped) {
}
