package com.resumeanalyzer.resume_backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "analyses")
public class Analysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String fileName;

    @Column(columnDefinition = "TEXT")
    private String technicalSkills;

    @Column(columnDefinition = "TEXT")
    private String softSkills;

    @Column
    private String experienceLevel;

    @Column(columnDefinition = "TEXT")
    private String recommendedJobs;

    @Column(columnDefinition = "TEXT")
    private String advice;

    @Column
    private Integer resumeScore;

    @Column(columnDefinition = "TEXT")
    private String jobMatches;

    @Column(columnDefinition = "TEXT")
    private String missingSkills;

    @Column(columnDefinition = "TEXT")
    private String strengths;

    @Column(columnDefinition = "TEXT")
    private String improvements;

    @Column(columnDefinition = "TEXT")
    private String resumeText;

    @Column(columnDefinition = "TEXT")
    private String profileInsights;

    @Column(nullable = false)
    private LocalDateTime analyzedAt = LocalDateTime.now();
}
