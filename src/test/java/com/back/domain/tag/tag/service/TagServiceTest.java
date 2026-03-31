package com.back.domain.tag.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.tag.tag.dto.TagResponse;
import com.back.domain.tag.tag.entity.Tag;
import com.back.domain.tag.tag.repository.TagRepository;

class TagServiceTest {

    private final TagRepository tagRepository = mock(TagRepository.class);
    private final TagService tagService = new TagService(tagRepository);

    @Test
    @DisplayName("태그 목록 조회 시 code/label을 태그 이름으로 반환한다")
    void getTags_returnsTagList() {
        Tag arrayTag = mock(Tag.class);
        Tag graphTag = mock(Tag.class);
        Tag blankTag = mock(Tag.class);

        when(arrayTag.getName()).thenReturn("array");
        when(graphTag.getName()).thenReturn("graph");
        when(blankTag.getName()).thenReturn("  ");

        when(tagRepository.findAllByOrderByNameAsc()).thenReturn(List.of(arrayTag, graphTag, blankTag));

        List<TagResponse> response = tagService.getTags();

        assertThat(response).containsExactly(new TagResponse("array", "array"), new TagResponse("graph", "graph"));
    }
}
