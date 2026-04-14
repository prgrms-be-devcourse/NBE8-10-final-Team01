package com.back.domain.tag.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.tag.tag.dto.TagResponse;
import com.back.domain.tag.tag.entity.Tag;
import com.back.domain.tag.tag.repository.TagRepository;

class TagServiceTest {

    private final TagRepository tagRepository = mock(TagRepository.class);
    private final TagService tagService = new TagService(tagRepository);

    @Test
    @DisplayName("태그 목록 조회 시 code/label과 난이도 목록을 함께 반환한다")
    void getTags_returnsTagList() {
        Tag arrayTag = mock(Tag.class);
        Tag graphTag = mock(Tag.class);
        Tag blankTag = mock(Tag.class);
        TagRepository.TagDifficultyView arrayEasy = mock(TagRepository.TagDifficultyView.class);
        TagRepository.TagDifficultyView arrayHard = mock(TagRepository.TagDifficultyView.class);
        TagRepository.TagDifficultyView graphMedium = mock(TagRepository.TagDifficultyView.class);

        when(arrayTag.getName()).thenReturn("array");
        when(graphTag.getName()).thenReturn("graph");
        when(blankTag.getName()).thenReturn("  ");
        when(arrayEasy.getTagName()).thenReturn("array");
        when(arrayEasy.getDifficulty()).thenReturn(DifficultyLevel.EASY);
        when(arrayHard.getTagName()).thenReturn("array");
        when(arrayHard.getDifficulty()).thenReturn(DifficultyLevel.HARD);
        when(graphMedium.getTagName()).thenReturn("graph");
        when(graphMedium.getDifficulty()).thenReturn(DifficultyLevel.MEDIUM);

        when(tagRepository.findAllByOrderByNameAsc()).thenReturn(List.of(arrayTag, graphTag, blankTag));
        when(tagRepository.findTagDifficulties()).thenReturn(List.of(arrayEasy, arrayHard, graphMedium));

        List<TagResponse> response = tagService.getTags();

        assertThat(response)
                .containsExactly(
                        new TagResponse("array", "array", List.of("EASY", "HARD")),
                        new TagResponse("graph", "graph", List.of("MEDIUM")));
    }
}
