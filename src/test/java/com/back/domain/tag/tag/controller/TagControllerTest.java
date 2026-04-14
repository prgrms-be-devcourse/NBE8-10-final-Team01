package com.back.domain.tag.tag.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.tag.tag.dto.TagResponse;
import com.back.domain.tag.tag.service.TagService;

class TagControllerTest {

    private final TagService tagService = mock(TagService.class);
    private final TagController tagController = new TagController(tagService);

    @Test
    @DisplayName("태그 컨트롤러는 서비스 결과를 그대로 반환한다")
    void getTags_returnsServiceResult() {
        List<TagResponse> response = List.of(
                new TagResponse("array", "array", List.of("EASY")),
                new TagResponse("graph", "graph", List.of("MEDIUM", "HARD")));

        when(tagService.getTags()).thenReturn(response);

        List<TagResponse> actual = tagController.getTags();

        assertThat(actual).isEqualTo(response);
        verify(tagService).getTags();
    }
}
