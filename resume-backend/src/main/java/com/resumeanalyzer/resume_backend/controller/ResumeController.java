package com.resumeanalyzer.resume_backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resumeanalyzer.resume_backend.model.Analysis;
import com.resumeanalyzer.resume_backend.model.User;
import com.resumeanalyzer.resume_backend.repository.AnalysisRepository;
import com.resumeanalyzer.resume_backend.repository.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    @Value("${groq.api.key}")
    private String GROQ_API_KEY;

    private static final String GROQ_API_URL =
            "https://api.groq.com/openai/v1/chat/completions";

    private final AnalysisRepository analysisRepository;
    private final UserRepository userRepository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    public ResumeController(AnalysisRepository analysisRepository,
                            UserRepository userRepository) {
        this.analysisRepository = analysisRepository;
        this.userRepository = userRepository;
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeResume(
            @RequestParam("file") MultipartFile file,
            Principal principal) {

        try {
            String resumeText = extractPdfText(file);
            String prompt = """
                    Analyze this resume as a hiring product. Return JSON only, with no markdown.
                    Schema:
                    {
                      "resumeScore": 78,
                      "technicalSkills": ["skill"],
                      "softSkills": ["skill"],
                      "experienceLevel": "Junior/Mid/Senior",
                      "recommendedJobs": ["role"],
                      "jobMatches": [{"role": "AI Engineer", "percentage": 82, "reason": "short reason"}],
                      "missingSkills": ["skill"],
                      "strengths": ["strength"],
                      "improvements": ["improvement"],
                      "advice": "one concise paragraph"
                    }
                    Score should be 0-100 and consider skills, experience, clarity, impact, and completeness.
                    Job matches should include 3 to 5 realistic roles ranked by fit.
                    Missing skills should be practical improvements for the recommended roles.
                    Resume text: %s
                    """.formatted(sanitize(resumeText));

            JsonNode parsed = hasGroqKey()
                    ? parseJsonContent(callGroq(prompt, 1400))
                    : buildLocalAnalysis(resumeText);
            ObjectNode enriched = parsed.deepCopy();
            enriched.put("resumeScore", calibrateScore(
                    enriched.path("resumeScore").asInt(0),
                    resumeText,
                    readStringArray(enriched.path("technicalSkills")),
                    readStringArray(enriched.path("softSkills")),
                    readStringArray(enriched.path("missingSkills"))
            ));

            if (principal != null) {
                userRepository.findByEmail(principal.getName())
                        .ifPresent(user -> {
                            Analysis analysis = new Analysis();
                            applyParsedAnalysis(analysis, enriched, file.getOriginalFilename(), resumeText);
                            analysis.setUser(user);
                            Analysis saved = analysisRepository.save(analysis);
                            enriched.put("analysisId", saved.getId());
                            enriched.put("fileName", saved.getFileName());
                            enriched.put("analyzedAt", saved.getAnalyzedAt().toString());
                        });
            }

            return ResponseEntity.ok(enriched);

        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Could not analyze this PDF. " + e.getMessage()));
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

    @PostMapping("/chat")
    public ResponseEntity<?> chatWithResume(@RequestBody Map<String, String> body,
                                            Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        Long analysisId = Long.valueOf(body.getOrDefault("analysisId", "0"));
        String question = body.getOrDefault("question", "").trim();
        if (question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Question is required"));
        }

        return getAnalysisForUser(analysisId, principal)
                .map(analysis -> {
                    try {
                        String prompt = """
                                You are a resume coach. Answer the user's question using only this resume analysis and resume text.
                                Keep the answer specific, practical, and concise.
                                Analysis: %s
                                Resume text: %s
                                Question: %s
                                """.formatted(analysisSummary(analysis), sanitize(analysis.getResumeText()), sanitize(question));
                        String answer = hasGroqKey()
                                ? callGroq(prompt, 700)
                                : localChatAnswer(question, analysis);
                        return ResponseEntity.ok(Map.of("answer", answer));
                    } catch (Exception e) {
                        return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Analysis not found")));
    }

    @PostMapping("/compare")
    public ResponseEntity<?> compareResumes(@RequestBody Map<String, Long> body,
                                            Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        Long firstId = body.get("firstId");
        Long secondId = body.get("secondId");
        if (firstId == null || secondId == null || firstId.equals(secondId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Choose two different analyses"));
        }

        var first = getAnalysisForUser(firstId, principal);
        var second = getAnalysisForUser(secondId, principal);
        if (first.isEmpty() || second.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Analysis not found"));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("first", comparisonCard(first.get()));
        result.put("second", comparisonCard(second.get()));
        result.put("winner", winner(first.get(), second.get()));
        result.put("skillGap", skillGap(first.get(), second.get()));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/profile")
    public ResponseEntity<?> analyzeProfile(@RequestBody Map<String, String> body,
                                            Principal principal) {
        String url = body.getOrDefault("url", "").trim();
        if (url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Profile URL is required"));
        }

        try {
            String profileText = fetchProfileText(url);
            String prompt = """
                    Extract career signals from this GitHub or LinkedIn profile content.
                    Return JSON only:
                    {
                      "profileUrl": "%s",
                      "skills": ["skill"],
                      "projectSignals": ["signal"],
                      "careerSummary": "short summary",
                      "resumeBoostAdvice": ["advice"]
                    }
                    Profile content: %s
                    """.formatted(sanitize(url), sanitize(profileText));
            JsonNode parsed = hasGroqKey()
                    ? parseJsonContent(callGroq(prompt, 900))
                    : buildLocalProfileInsights(url);

            String analysisIdText = body.get("analysisId");
            if (principal != null && analysisIdText != null && !analysisIdText.isBlank()) {
                getAnalysisForUser(Long.valueOf(analysisIdText), principal)
                        .ifPresent(analysis -> {
                            analysis.setProfileInsights(parsed.toString());
                            analysisRepository.save(analysis);
                        });
            }

            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/report/{id}")
    public ResponseEntity<byte[]> downloadReport(@PathVariable Long id,
                                                 Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        return getAnalysisForUser(id, principal)
                .map(analysis -> {
                    try {
                        byte[] pdf = buildPdfReport(analysis);
                        String fileName = "resume-analysis-" + analysis.getId() + ".pdf";
                        return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                        "attachment; filename=\"" + fileName + "\"")
                                .contentType(MediaType.APPLICATION_PDF)
                                .body(pdf);
                    } catch (IOException e) {
                        return ResponseEntity.status(500).body(new byte[0]);
                    }
                })
                .orElse(ResponseEntity.status(404).build());
    }

    private String extractPdfText(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String callGroq(String prompt, int maxTokens) throws IOException, InterruptedException {
        Map<String, Object> payload = Map.of(
                "model", "llama-3.3-70b-versatile",
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", maxTokens
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GROQ_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());
        if (!root.has("choices")) {
            throw new IOException("AI provider did not return an analysis. Check your Groq API key.");
        }
        return root.get("choices").get(0).get("message").get("content").asText();
    }

    private JsonNode parseJsonContent(String text) throws IOException {
        String cleanJson = text
                .replace("```json", "")
                .replace("```", "")
                .trim();
        return mapper.readTree(cleanJson);
    }

    private void applyParsedAnalysis(Analysis analysis, JsonNode parsed,
                                     String fileName, String resumeText) {
        analysis.setFileName(fileName == null ? "resume.pdf" : fileName);
        analysis.setResumeText(resumeText);
        analysis.setResumeScore(parsed.path("resumeScore").asInt(0));
        analysis.setTechnicalSkills(parsed.path("technicalSkills").toString());
        analysis.setSoftSkills(parsed.path("softSkills").toString());
        analysis.setExperienceLevel(parsed.path("experienceLevel").asText("Not specified"));
        analysis.setRecommendedJobs(parsed.path("recommendedJobs").toString());
        analysis.setJobMatches(parsed.path("jobMatches").toString());
        analysis.setMissingSkills(parsed.path("missingSkills").toString());
        analysis.setStrengths(parsed.path("strengths").toString());
        analysis.setImprovements(parsed.path("improvements").toString());
        analysis.setAdvice(parsed.path("advice").asText(""));
    }

    private java.util.Optional<Analysis> getAnalysisForUser(Long id, Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .flatMap(user -> analysisRepository.findByIdAndUser(id, user));
    }

    private String analysisSummary(Analysis analysis) {
        return """
                File: %s
                Score: %s
                Technical skills: %s
                Soft skills: %s
                Experience: %s
                Jobs: %s
                Job matches: %s
                Missing skills: %s
                Strengths: %s
                Improvements: %s
                Advice: %s
                """.formatted(
                analysis.getFileName(),
                analysis.getResumeScore(),
                analysis.getTechnicalSkills(),
                analysis.getSoftSkills(),
                analysis.getExperienceLevel(),
                analysis.getRecommendedJobs(),
                analysis.getJobMatches(),
                analysis.getMissingSkills(),
                analysis.getStrengths(),
                analysis.getImprovements(),
                analysis.getAdvice()
        );
    }

    private Map<String, Object> comparisonCard(Analysis analysis) {
        Map<String, Object> card = new HashMap<>();
        card.put("id", analysis.getId());
        card.put("fileName", analysis.getFileName());
        card.put("score", analysis.getResumeScore() == null ? 0 : analysis.getResumeScore());
        card.put("experienceLevel", analysis.getExperienceLevel());
        card.put("technicalSkills", safeJsonArray(analysis.getTechnicalSkills()));
        card.put("strengths", safeJsonArray(analysis.getStrengths()));
        return card;
    }

    private String winner(Analysis first, Analysis second) {
        int firstScore = first.getResumeScore() == null ? 0 : first.getResumeScore();
        int secondScore = second.getResumeScore() == null ? 0 : second.getResumeScore();
        if (firstScore == secondScore) return "Tie";
        return firstScore > secondScore ? first.getFileName() : second.getFileName();
    }

    private Map<String, List<String>> skillGap(Analysis first, Analysis second) {
        List<String> firstSkills = safeJsonArray(first.getTechnicalSkills());
        List<String> secondSkills = safeJsonArray(second.getTechnicalSkills());
        Map<String, List<String>> gap = new HashMap<>();
        gap.put("onlyInFirst", difference(firstSkills, secondSkills));
        gap.put("onlyInSecond", difference(secondSkills, firstSkills));
        return gap;
    }

    private List<String> difference(List<String> source, List<String> other) {
        return source.stream()
                .filter(skill -> other.stream().noneMatch(item -> item.equalsIgnoreCase(skill)))
                .toList();
    }

    private List<String> safeJsonArray(String value) {
        List<String> values = new ArrayList<>();
        if (value == null || value.isBlank()) return values;
        try {
            JsonNode node = mapper.readTree(value);
            if (node.isArray()) {
                node.forEach(item -> values.add(item.asText()));
            }
        } catch (Exception ignored) {
            return values;
        }
        return values;
    }

    private String fetchProfileText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "ResumeAnalyzer/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body().replaceAll("<[^>]*>", " ");
            return body.length() > 6000 ? body.substring(0, 6000) : body;
        } catch (Exception e) {
            return "Profile URL: " + url + ". The page could not be fetched directly; infer useful profile review guidance from the URL only.";
        }
    }

    private byte[] buildPdfReport(Analysis analysis) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);

            PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPageContentStream content = new PDPageContentStream(document, page);
            float y = 740;

            y = writeLine(content, titleFont, 18, 50, y, "Resume Analysis Report");
            y = writeLine(content, bodyFont, 11, 50, y - 8,
                    "File: " + analysis.getFileName());
            y = writeLine(content, bodyFont, 11, 50, y,
                    "Generated from analysis date: " +
                            analysis.getAnalyzedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));

            y = writeSection(content, titleFont, bodyFont, y - 18, "Score",
                    (analysis.getResumeScore() == null ? 0 : analysis.getResumeScore()) + " / 100");
            y = writeSection(content, titleFont, bodyFont, y, "Experience",
                    analysis.getExperienceLevel());
            y = writeSection(content, titleFont, bodyFont, y, "Technical Skills",
                    String.join(", ", safeJsonArray(analysis.getTechnicalSkills())));
            y = writeSection(content, titleFont, bodyFont, y, "Soft Skills",
                    String.join(", ", safeJsonArray(analysis.getSoftSkills())));
            y = writeSection(content, titleFont, bodyFont, y, "Job Matches",
                    analysis.getJobMatches());
            y = writeSection(content, titleFont, bodyFont, y, "Missing Skills",
                    String.join(", ", safeJsonArray(analysis.getMissingSkills())));
            y = writeSection(content, titleFont, bodyFont, y, "Strengths",
                    String.join(", ", safeJsonArray(analysis.getStrengths())));
            y = writeSection(content, titleFont, bodyFont, y, "Improvements",
                    String.join(", ", safeJsonArray(analysis.getImprovements())));
            writeSection(content, titleFont, bodyFont, y, "AI Advice", analysis.getAdvice());

            content.close();
            document.save(output);
            return output.toByteArray();
        }
    }

    private float writeSection(PDPageContentStream content, PDType1Font titleFont,
                               PDType1Font bodyFont, float y, String title, String text) throws IOException {
        y = writeLine(content, titleFont, 12, 50, y - 12, title);
        for (String line : wrap(text == null ? "" : text, 92)) {
            y = writeLine(content, bodyFont, 10, 50, y, line);
        }
        return y - 4;
    }

    private float writeLine(PDPageContentStream content, PDType1Font font,
                            int size, float x, float y, String text) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText((text == null ? "" : text).replaceAll("[\\r\\n]", " "));
        content.endText();
        return y - 15;
    }

    private List<String> wrap(String text, int maxLength) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.replace("\"", "").split("\\s+")) {
            if (current.length() + word.length() + 1 > maxLength) {
                lines.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) current.append(" ");
            current.append(word);
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }

    private String sanitize(String value) {
        if (value == null) return "";
        return value
                .replace("\\", " ")
                .replace("\"", "'")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ")
                .replaceAll("[\\x00-\\x1F\\x7F]", " ");
    }

    private boolean hasGroqKey() {
        return GROQ_API_KEY != null
                && !GROQ_API_KEY.isBlank()
                && !"YOUR_KEY_HERE".equals(GROQ_API_KEY);
    }

    private ObjectNode buildLocalAnalysis(String resumeText) {
        String lowerText = resumeText == null ? "" : resumeText.toLowerCase();
        List<String> technicalSkills = findKnownSkills(lowerText);
        List<String> softSkills = findKnownSoftSkills(lowerText);
        String experienceLevel = inferExperienceLevel(lowerText);
        List<String> missingSkills = missingSkillsFor(technicalSkills);
        int score = calculateScore(lowerText, technicalSkills, softSkills, missingSkills);

        ObjectNode analysis = mapper.createObjectNode();
        analysis.put("resumeScore", score);
        addTextArray(analysis, "technicalSkills", technicalSkills);
        addTextArray(analysis, "softSkills", softSkills);
        analysis.put("experienceLevel", experienceLevel);
        addTextArray(analysis, "recommendedJobs", recommendedJobs(technicalSkills));
        addJobMatches(analysis, technicalSkills, experienceLevel);
        addTextArray(analysis, "missingSkills", missingSkills);
        addTextArray(analysis, "strengths", strengthsFor(technicalSkills, softSkills, lowerText));
        addTextArray(analysis, "improvements", improvementsFor(missingSkills, lowerText));
        analysis.put("advice", "Add measurable project impact, tighten role-specific keywords, and include the missing skills that match your target jobs.");
        return analysis;
    }

    private List<String> findKnownSkills(String text) {
        List<String> catalog = List.of(
                "Java", "Spring Boot", "React", "JavaScript", "TypeScript", "HTML",
                "CSS", "SQL", "PostgreSQL", "MySQL", "Python", "Machine Learning",
                "Data Analysis", "REST API", "Git", "Docker", "Kubernetes", "AWS",
                "Node.js", "MongoDB", "TensorFlow", "NLP"
        );
        List<String> found = catalog.stream()
                .filter(skill -> text.contains(skill.toLowerCase()))
                .limit(8)
                .toList();
        return found.isEmpty() ? List.of("Communication", "Problem Solving", "Documentation") : found;
    }

    private List<String> findKnownSoftSkills(String text) {
        List<String> catalog = List.of("Leadership", "Communication", "Teamwork", "Problem Solving", "Adaptability", "Time Management");
        List<String> found = catalog.stream()
                .filter(skill -> text.contains(skill.toLowerCase().replace(" ", "")) || text.contains(skill.toLowerCase()))
                .limit(5)
                .toList();
        return found.isEmpty() ? List.of("Communication", "Problem Solving", "Teamwork") : found;
    }

    private String inferExperienceLevel(String text) {
        if (text.matches(".*\\b(5\\+|6\\+|7\\+|8\\+|senior|lead|manager)\\b.*")) return "Senior";
        if (text.matches(".*\\b(2\\+|3\\+|4\\+|internship|developer|engineer)\\b.*")) return "Mid";
        return "Junior";
    }

    private int calculateScore(String text, List<String> technicalSkills, List<String> softSkills,
                               List<String> missingSkills) {
        return calibrateScore(0, text, technicalSkills, softSkills, missingSkills);
    }

    private int calibrateScore(int aiScore, String resumeText, List<String> technicalSkills,
                               List<String> softSkills, List<String> missingSkills) {
        String text = resumeText == null ? "" : resumeText.toLowerCase();
        int wordCount = text.isBlank() ? 0 : text.trim().split("\\s+").length;

        int score = 38;
        score += Math.min(technicalSkills.size() * 4, 24);
        score += Math.min(softSkills.size() * 3, 12);
        score += Math.min(wordCount / 55, 10);

        if (text.contains("project")) score += 5;
        if (text.contains("experience") || text.contains("internship")) score += 5;
        if (text.contains("education") || text.contains("degree") || text.contains("university")) score += 4;
        if (text.contains("github") || text.contains("linkedin") || text.contains("portfolio")) score += 4;
        if (text.matches(".*\\b(achieved|built|improved|reduced|increased|developed|designed|deployed|automated)\\b.*")) score += 6;
        if (text.matches(".*\\b(\\d+%|\\d+ users|\\d+ projects|\\d+ months|\\d+ years)\\b.*")) score += 5;
        score -= Math.min(missingSkills.size() * 2, 10);

        int contentScore = Math.max(42, Math.min(score, 96));
        if (aiScore <= 0) return contentScore;

        int blendedScore = Math.round((aiScore * 0.35f) + (contentScore * 0.65f));
        return Math.max(42, Math.min(blendedScore, 96));
    }

    private List<String> recommendedJobs(List<String> skills) {
        String joined = String.join(" ", skills).toLowerCase();
        if (joined.contains("machine") || joined.contains("python") || joined.contains("nlp")) {
            return List.of("AI Engineer", "Machine Learning Engineer", "Data Analyst");
        }
        if (joined.contains("react") || joined.contains("javascript") || joined.contains("css")) {
            return List.of("Frontend Developer", "React Developer", "Full Stack Developer");
        }
        if (joined.contains("spring") || joined.contains("java") || joined.contains("sql")) {
            return List.of("Backend Developer", "Java Developer", "Full Stack Developer");
        }
        return List.of("Software Developer", "Junior Developer", "Technical Support Engineer");
    }

    private void addJobMatches(ObjectNode analysis, List<String> skills, String experienceLevel) {
        ArrayNode matches = mapper.createArrayNode();
        List<String> jobs = recommendedJobs(skills);
        int base = "Senior".equals(experienceLevel) ? 84 : "Mid".equals(experienceLevel) ? 76 : 68;
        for (int i = 0; i < jobs.size(); i++) {
            ObjectNode job = mapper.createObjectNode();
            job.put("role", jobs.get(i));
            job.put("percentage", Math.max(base - (i * 7), 45));
            job.put("reason", "Matched from detected skills and resume completeness.");
            matches.add(job);
        }
        analysis.set("jobMatches", matches);
    }

    private List<String> missingSkillsFor(List<String> skills) {
        List<String> target = List.of("Docker", "Kubernetes", "AWS", "System Design", "Testing");
        List<String> missing = target.stream()
                .filter(skill -> skills.stream().noneMatch(found -> found.equalsIgnoreCase(skill)))
                .limit(5)
                .toList();
        return missing.isEmpty() ? List.of("Advanced Projects", "Metrics", "Deployment Experience") : missing;
    }

    private List<String> strengthsFor(List<String> technicalSkills, List<String> softSkills, String text) {
        List<String> strengths = new ArrayList<>();
        strengths.add("Shows " + technicalSkills.size() + " relevant technical skill signals.");
        strengths.add("Includes soft skill indicators such as " + String.join(", ", softSkills.subList(0, Math.min(2, softSkills.size()))) + ".");
        if (text.contains("project")) strengths.add("Project work is visible in the resume.");
        return strengths;
    }

    private List<String> improvementsFor(List<String> missingSkills, String text) {
        List<String> improvements = new ArrayList<>();
        improvements.add("Add quantified achievements such as performance, users, accuracy, or time saved.");
        improvements.add("Add or strengthen these target skills: " + String.join(", ", missingSkills) + ".");
        if (!text.contains("github")) improvements.add("Include a GitHub or portfolio link for proof of work.");
        return improvements;
    }

    private void addTextArray(ObjectNode node, String fieldName, List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        node.set(fieldName, array);
    }

    private List<String> readStringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private String localChatAnswer(String question, Analysis analysis) {
        String lowerQuestion = question.toLowerCase();
        if (lowerQuestion.contains("job") || lowerQuestion.contains("role")) {
            return "Your strongest role matches are " + analysis.getRecommendedJobs() + ". Improve the fit by adding proof-heavy projects and role-specific keywords.";
        }
        if (lowerQuestion.contains("improve") || lowerQuestion.contains("missing")) {
            return "Focus on these missing skills: " + analysis.getMissingSkills() + ". Also add measurable impact under each project or experience item.";
        }
        return "This resume scores " + analysis.getResumeScore() + "/100. The main next step is to make the resume more measurable and targeted to one job role.";
    }

    private ObjectNode buildLocalProfileInsights(String url) {
        ObjectNode insights = mapper.createObjectNode();
        insights.put("profileUrl", url);
        addTextArray(insights, "skills", List.of("Repository Review", "Project Documentation", "Technical Portfolio"));
        addTextArray(insights, "projectSignals", List.of("Use pinned projects to highlight role fit.", "Add README files with tech stack and screenshots."));
        insights.put("careerSummary", "Profile URL captured. Add a real Groq key for deeper AI extraction from public profile content.");
        addTextArray(insights, "resumeBoostAdvice", List.of("Link your strongest project in the resume.", "Mirror profile skills in the resume skills section."));
        return insights;
    }
}
