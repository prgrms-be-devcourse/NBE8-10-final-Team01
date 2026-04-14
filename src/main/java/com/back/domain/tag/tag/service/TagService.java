package com.back.domain.tag.tag.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.tag.tag.dto.TagResponse;
import com.back.domain.tag.tag.repository.TagRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private static final List<DifficultyLevel> DIFFICULTY_ORDER =
            List.of(DifficultyLevel.EASY, DifficultyLevel.MEDIUM, DifficultyLevel.HARD);

    private final TagRepository tagRepository;

    public List<TagResponse> getTags() {
        Map<String, Set<DifficultyLevel>> difficultyByTag = tagRepository.findTagDifficulties().stream()
                .filter(row -> row.getTagName() != null && row.getDifficulty() != null)
                .filter(row -> !row.getTagName().trim().isBlank())
                .collect(Collectors.groupingBy(
                        row -> row.getTagName().trim(),
                        Collectors.mapping(TagRepository.TagDifficultyView::getDifficulty, Collectors.toSet())));

        return tagRepository.findAllByOrderByNameAsc().stream()
                .map(tag -> tag.getName() == null ? null : tag.getName().trim())
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .map(name -> new TagResponse(name, name, toDifficultyNames(difficultyByTag.get(name))))
                .toList();
    }

    private List<String> toDifficultyNames(Set<DifficultyLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }

        return DIFFICULTY_ORDER.stream()
                .filter(levels::contains)
                .map(DifficultyLevel::name)
                .toList();
    }
}
