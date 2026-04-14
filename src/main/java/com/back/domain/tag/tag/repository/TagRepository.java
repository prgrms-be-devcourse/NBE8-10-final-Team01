package com.back.domain.tag.tag.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.tag.tag.entity.Tag;

public interface TagRepository extends JpaRepository<Tag, Long> {
    interface TagDifficultyView {
        String getTagName();

        DifficultyLevel getDifficulty();
    }

    List<Tag> findAllByOrderByNameAsc();

    Optional<Tag> findByName(String name);

    @Query(
            """
            select t.name as tagName, p.difficulty as difficulty
            from ProblemTagConnect ptc
            join ptc.tag t
            join ptc.problem p
            where t.name is not null
              and trim(t.name) <> ''
            group by t.name, p.difficulty
            order by t.name asc
            """)
    List<TagDifficultyView> findTagDifficulties();
}
