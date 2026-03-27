package com.back.domain.problem.solo.submission.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.problem.solo.submission.entity.SoloSubmission;

public interface SoloSubmissionRepository extends JpaRepository<SoloSubmission, Long> {}
