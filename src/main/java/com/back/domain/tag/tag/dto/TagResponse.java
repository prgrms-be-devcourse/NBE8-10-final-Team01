package com.back.domain.tag.tag.dto;

import java.util.List;

public record TagResponse(String code, String label, List<String> difficulties) {}
