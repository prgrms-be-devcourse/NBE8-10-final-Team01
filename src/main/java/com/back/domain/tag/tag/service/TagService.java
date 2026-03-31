package com.back.domain.tag.tag.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.tag.tag.dto.TagResponse;
import com.back.domain.tag.tag.repository.TagRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;

    public List<TagResponse> getTags() {
        return tagRepository.findAllByOrderByNameAsc().stream()
                .map(tag -> tag.getName() == null ? null : tag.getName().trim())
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .map(name -> new TagResponse(name, name))
                .toList();
    }
}
