package com.resumeanalyzer.resume_backend.repository;

import com.resumeanalyzer.resume_backend.model.Analysis;
import com.resumeanalyzer.resume_backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisRepository extends JpaRepository<Analysis, Long> {
    List<Analysis> findByUserOrderByAnalyzedAtDesc(User user);
    Optional<Analysis> findByIdAndUser(Long id, User user);
}
