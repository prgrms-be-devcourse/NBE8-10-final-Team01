package com.back.domain.tag.tag.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.tag.tag.entity.Tag;

public interface TagRepository extends JpaRepository<Tag, Long> {
    List<Tag> findAllByOrderByNameAsc();

    Optional<Tag> findByName(String name);
}
