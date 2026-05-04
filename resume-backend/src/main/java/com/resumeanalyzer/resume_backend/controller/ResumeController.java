package com.resumeanalyzer.resume_backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumeanalyzer.resume_backend.model.Analysis;
import com.resumeanalyzer.resume_backend.repository.AnalysisRepository;
import com.resumeanalyzer.resume_backend.repository.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    @Value("${groq.api.key}")
    private String GROQ_API_KEY;

    private static final String GROQ_API_URL =
            "https://api.groq.com/openai/v1/chat/completions";

    private final AnalysisRepository analysisRepository;
    private final UserRepository userRepository;

    public ResumeController(AnalysisRepository analysisRepository,
                            UserRepository userRepository) {
        this.analysisRepository = analysisRepository;
        this.userRepository = userRepository;
    }

    @PostMapping("/analyze")
    public ResponseEntity<String> analyzeResume(
            @RequestParam("file") MultipartFile file,
            Principal principal) {

        try {
            PDDocument document = Loader.loadPDF(file.getBytes());
            PDFTextStripper stripper = new PDFTextStripper();
            String resumeText = stripper.getText(document);
            document.close();

            String prompt = "Analyze this resume and provide: " +
                    "1. Top 5 technical skills found " +
                    "2. Top 5 soft skills found " +
                    "3. Experience level (Junior/Mid/Senior) " +
                    "4. Top 3 job roles this person is suited for " +
                    "5. One sentence of improvement advice. " +
                    "Format your response as JSON only, no extra text, no markdown, like this: " +
                    "{\"technicalSkills\": [\"skill1\", \"skill2\", \"skill3\", \"skill4\", \"skill5\"], " +
                    "\"softSkills\": [\"skill1\", \"skill2\", \"skill3\", \"skill4\", \"skill5\"], " +
                    "\"experienceLevel\": \"Mid\", " +
                    "\"recommendedJobs\": [\"job1\", \"job2\", \"job3\"], " +
                    "\"advice\": \"Your improvement advice here\"} " +
                    "Resume text: " + resumeText
                    .replace("\\", " ")
                    .replace("\"", "'")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace("\t", " ")
                    .replaceAll("[\\x00-\\x1F\\x7F]", " ");

            String requestBody = """
                {
                    "model": "llama-3.3-70b-versatile",
                    "messages": [
                        {
                            "role": "user",
                            "content": "%s"
                        }
                    ],
                    "max_tokens": 1000
                }
                """.formatted(prompt.replace("\"", "\\\""));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + GROQ_API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();

            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(responseBody);
                String text = root.get("choices").get(0)
                        .get("message").get("content").asText();
                String cleanJson = text
                        .replace("```json", "")
                        .replace("```", "")
                        .trim();
                JsonNode parsed = mapper.readTree(cleanJson);

                if (principal != null) {
                    userRepository.findByEmail(principal.getName())
                            .ifPresent(user -> {
                                Analysis analysis = new Analysis();
                                analysis.setUser(user);
                                analysis.setFileName(file.getOriginalFilename());
                                analysis.setTechnicalSkills(
                                        parsed.get("technicalSkills").toString());
                                analysis.setSoftSkills(
                                        parsed.get("softSkills").toString());
                                analysis.setExperienceLevel(
                                        parsed.get("experienceLevel").asText());
                                analysis.setRecommendedJobs(
                                        parsed.get("recommendedJobs").toString());
                                analysis.setAdvice(
                                        parsed.get("advice").asText());
                                analysisRepository.save(analysis);
                            });
                }
            } catch (Exception e) {
                System.out.println("Could not save: " + e.getMessage());
            }

            return ResponseEntity.ok(responseBody);

        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(500)
                    .body("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<Analysis>> getHistory(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return userRepository.findByEmail(principal.getName())
                .map(user -> ResponseEntity.ok(
                        analysisRepository.findByUserOrderByAnalyzedAtDesc(user)))
                .orElse(ResponseEntity.status(404).build());
    }
}