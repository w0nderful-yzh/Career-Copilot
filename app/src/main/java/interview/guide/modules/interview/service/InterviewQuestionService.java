package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 面试问题生成服务
 * 无简历：单次 Skill 驱动出题
 * 有简历：并行调用（简历题 60% + 方向题 40%）
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private static final String DEFAULT_QUESTION_TYPE = "GENERAL";
    /** 组内候选池容量上限（P4-4b）：素材池可以比追问预算更大，但不无限堆 */
    private static final int MAX_FOLLOW_UP_CANDIDATES = 5;
    private static final double RESUME_QUESTION_RATIO = 0.6;

    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        \n\n# 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
        "junior", "校招/0-1年经验。考察基础概念和简单应用。",
        "mid", "1-3年经验。考察原理理解和实战经验。",
        "senior", "3年+经验。考察架构设计和深度调优。"
    );

    /** 整体难度枚举 → 单题难度基线（1-5），供生成时让模型在基线上下浮动 */
    private static final Map<String, Integer> DIFFICULTY_BASE = Map.of(
        "junior", 2,
        "mid", 3,
        "senior", 4
    );

    private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
        {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"},
        {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"},
        {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"},
        {"你如何保证代码质量？介绍你实践过的有效手段。", "GENERAL", "综合能力"},
        {"描述一个你做过的技术优化案例，优化的动机、方案和效果。", "GENERAL", "综合能力"},
        {"你在团队协作中遇到过最大的分歧是什么？如何解决的？", "GENERAL", "综合能力"},
    };

    private final PromptTemplate skillSystemPromptTemplate;
    private final PromptTemplate skillUserPromptTemplate;
    private final PromptTemplate resumeSystemPromptTemplate;
    private final PromptTemplate resumeUserPromptTemplate;
    private final PromptTemplate candidatePrepSystemPromptTemplate;
    private final PromptTemplate candidatePrepUserPromptTemplate;
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    private final BeanOutputConverter<FollowUpListDTO> followUpConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewSkillService skillService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final ExecutorService questionExecutor;
    /** 组内候选池容量（P4-4b）：预置多少条追问素材，与运行期追问预算解耦 */
    private final int followUpCandidateCount;
    /** 运行期追问预算（P4-4b）：单组最多实际追问几条 */
    private final int followUpBudget;
    /** 后台预备阈值（P4-4b）：组内未问追问低于此值时预备；0 = 关闭 */
    private final int backgroundPrepThreshold;

    /**
     * P4-1 出题 schema（对齐自适应引擎 §10）：
     * 主问题携带数值难度 difficulty(1-5) 与考察要点 expectedPoints；
     * followUps 由纯文本升级为带语义类型 followUpType 与 expectedPoints 的追问对象。
     * 包可见：供同包单测直接构造验证生成契约（非对外 API）。
     */
    record QuestionListDTO(List<QuestionDTO> questions) {}

    /** 后台预备追问的输出 schema（P4-4b）：只要 followUps，不重复生成主问题 */
    record FollowUpListDTO(List<FollowUpDTO> followUps) {}

    record QuestionDTO(String question, String type, String category,
                       String topicSummary, Integer difficulty,
                       List<String> expectedPoints, List<FollowUpDTO> followUps) {}

    record FollowUpDTO(String question, String followUpType, List<String> expectedPoints) {}

    public InterviewQuestionService(
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewSkillService skillService,
            InterviewQuestionProperties properties,
            ResourceLoader resourceLoader,
            LlmProviderRegistry llmProviderRegistry,
            PromptSanitizer promptSanitizer) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.skillSystemPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionSystemPromptPath());
        this.skillUserPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionUserPromptPath());
        this.resumeSystemPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionSystemPromptPath());
        this.resumeUserPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionUserPromptPath());
        this.candidatePrepSystemPromptTemplate = loadTemplate(resourceLoader, properties.getCandidatePrepSystemPromptPath());
        this.candidatePrepUserPromptTemplate = loadTemplate(resourceLoader, properties.getCandidatePrepUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpConverter = new BeanOutputConverter<>(FollowUpListDTO.class);
        // 候选容量：夹取到 [1, MAX_FOLLOW_UP_CANDIDATES]，至少留一条素材
        this.followUpCandidateCount = Math.max(1,
            Math.min(properties.getFollowUpCandidateCount(), MAX_FOLLOW_UP_CANDIDATES));
        // 追问预算：不超过候选容量，允许配置为 0（不追问）
        this.followUpBudget = Math.max(0,
            Math.min(properties.getFollowUpCount(), this.followUpCandidateCount));
        this.backgroundPrepThreshold = Math.max(0,
            Math.min(properties.getBackgroundPrepThreshold(), this.followUpCandidateCount));
    }

    private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
        return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
    }

    @PreDestroy
    void destroy() {
        questionExecutor.shutdownNow();
    }

    /**
     * 为一个主问题预备额外追问候选（P4-4b，后台异步）。
     *
     * <p>与同轮受限生成不同：这里不在用户等待路径上，用**后台档**预算，产出几条带不同
     * followUpType 的追问素材。题目标识由 {@code createFollowUp} 现场分配；父链已挂好，
     * 来源/代次与去重、重排由调用方（消费者）补齐。失败返回空列表，不影响实时循环。
     */
    public List<InterviewQuestionDTO> generateFollowUpCandidatesForMain(
            String llmProvider, InterviewQuestionDTO main, String resumeText, int count) {
        if (main == null || main.question() == null || main.question().isBlank() || count <= 0) {
            return List.of();
        }
        ChatClient prepChatClient = llmProviderRegistry.getPlainChatClient(llmProvider);
        Map<String, Object> variables = new HashMap<>();
        variables.put("mainQuestion", main.question());
        variables.put("category", main.category() != null ? main.category() : "");
        variables.put("topicSummary", main.topicSummary() != null ? main.topicSummary() : "");
        variables.put("difficulty", main.difficulty() != null ? main.difficulty() : 3);
        variables.put("expectedPoints", main.expectedPoints() == null || main.expectedPoints().isEmpty()
            ? "（无）" : String.join("；", main.expectedPoints()));
        variables.put("resumeSnippet", truncateResumeSnippet(resumeText));
        variables.put("count", count);
        try {
            String systemPrompt = candidatePrepSystemPromptTemplate.render()
                + "\n\n" + followUpConverter.getFormat();
            String userPrompt = candidatePrepUserPromptTemplate.render(variables);
            FollowUpListDTO dto = structuredOutputInvoker.invoke(
                prepChatClient, systemPrompt, userPrompt, followUpConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "后台预备追问失败：", "后台预备追问", log);
            List<FollowUpDTO> followUps = sanitizeFollowUps(dto == null ? null : dto.followUps());
            List<InterviewQuestionDTO> result = new ArrayList<>();
            int index = 0;
            for (FollowUpDTO followUp : followUps) {
                result.add(InterviewQuestionDTO.createFollowUp(
                    index, followUp.question(), main.type(), main.category(),
                    main.questionId(), index + 1, followUp.followUpType(), followUp.expectedPoints()));
                index++;
            }
            log.info("后台预备追问完成: main={}, 产出={}", main.questionId(), result.size());
            return result;
        } catch (Exception e) {
            log.warn("后台预备追问失败（不影响实时循环）: main={}", main.questionId(), e);
            return List.of();
        }
    }

    /** 后台预备只需一段简历作为背景，与逐轮上下文同量级裁剪，避免把整份简历喂给模型 */
    private static String truncateResumeSnippet(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return "（本场没有简历依据）";
        }
        String stripped = resumeText.strip();
        int limit = 600;
        return stripped.length() <= limit ? stripped : stripped.substring(0, limit) + "…（已截断）";
    }

    public List<InterviewQuestionDTO> generateQuestionsBySkill(
            String llmProvider,
            String skillId,
            String difficulty,
            String resumeText,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions,
            List<CategoryDTO> customCategories,
            String jdText,
            List<String> focusCategories) {

        SkillDTO skill = resolveSkill(skillId, customCategories, jdText, focusCategories);
        String difficultyDesc = resolveDifficulty(difficulty);
        int difficultyBase = DIFFICULTY_BASE.getOrDefault(
            difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY, 3);
        ChatClient questionChatClient =
            llmProviderRegistry.getPlainChatClient(llmProvider);

        boolean hasResume = resumeText != null && !resumeText.isBlank();
        String historicalSection = buildHistoricalSection(historicalQuestions);
        if (!hasResume) {
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, difficultyBase,
                questionCount, historicalSection);
        }

        int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
        int directionCount = questionCount - resumeCount;

        log.info("并行出题: skill={}, total={}, resumeCount={}, directionCount={}",
            skillId, questionCount, resumeCount, directionCount);

        CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
            () -> generateResumeQuestions(questionChatClient, resumeText, resumeCount, skill,
                difficultyDesc, difficultyBase, historicalSection),
            questionExecutor);

        CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
            () -> generateDirectionOnly(questionChatClient, skill, difficultyDesc, difficultyBase,
                directionCount, historicalSection),
            questionExecutor);

        List<InterviewQuestionDTO> resumeQuestions;
        List<InterviewQuestionDTO> directionQuestions;
        try {
            resumeQuestions = resumeFuture.join();
        } catch (CompletionException e) {
            log.error("简历题生成失败，降级为全方向题", e.getCause());
            directionFuture.cancel(true);
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, difficultyBase,
                questionCount, historicalSection);
        }

        try {
            directionQuestions = directionFuture.join();
        } catch (CompletionException e) {
            log.error("方向题生成失败，降级为全简历题", e.getCause());
            if (resumeQuestions.isEmpty()) {
                return generateFallbackQuestions(skill, questionCount, difficultyBase);
            }
            return resumeQuestions;
        }

        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
            log.warn("简历题和方向题均为空，回退到默认问题");
            return generateFallbackQuestions(skill, questionCount, difficultyBase);
        }

        List<InterviewQuestionDTO> merged = mergeQuestionBatches(resumeQuestions, directionQuestions);
        log.info("并行出题成功: 简历题={}, 方向题={}, 合计={}",
            resumeQuestions.size(), directionQuestions.size(), merged.size());
        return merged;
    }

    private List<InterviewQuestionDTO> generateResumeQuestions(
            ChatClient questionClient, String resumeText, int questionCount,
            SkillDTO skill, String difficultyDesc, int difficultyBase, String historicalSection) {
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCandidateCount);
            variables.put("difficultyBase", difficultyBase);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("resumeText", resumeText);
            variables.put("historicalSection", historicalSection);

            String systemPrompt = resumeSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + "\n\n" + outputConverter.getFormat();
            String userPrompt = resumeUserPromptTemplate.render(variables);

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "简历题生成失败：", "简历题", log);

            List<InterviewQuestionDTO> questions = convertToQuestions(dto, difficultyBase);
            questions = capToMainCount(questions, questionCount);
            log.info("简历题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("简历题生成异常: {}", e.getMessage(), e);
            throw e;
        }
    }

    private List<InterviewQuestionDTO> generateDirectionOnly(
            ChatClient questionClient, SkillDTO skill, String difficultyDesc, int difficultyBase,
            int questionCount, String historicalSection) {
        Map<String, Integer> allocation = skillService.calculateAllocation(skill.categories(), questionCount);
        String allocationTable = skillService.buildAllocationDescription(allocation, skill.categories());

        log.info("方向题生成: skill={}, total={}, allocation={}",
            skill.id(), questionCount, allocation);

        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCandidateCount);
            variables.put("difficultyBase", difficultyBase);
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("allocationTable", allocationTable);
            variables.put("historicalSection", historicalSection);
            variables.put("referenceSection", skillService.buildReferenceSection(skill, allocation));
            variables.put("jdSection", buildJdSection(skill.sourceJd()));

            String systemPrompt = skillSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + GENERIC_MODE_SYSTEM_APPEND
                + outputConverter.getFormat();
            String userPrompt = skillUserPromptTemplate.render(variables);

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "方向题生成失败：", "方向题", log);

            List<InterviewQuestionDTO> questions = convertToQuestions(dto, difficultyBase);
            if (questions.stream().filter(q -> !q.isFollowUp()).count() == 0) {
                log.warn("方向题返回空题单，回退到默认问题");
                return generateFallbackQuestions(skill, questionCount, difficultyBase);
            }
            questions = capToMainCount(questions, questionCount);
            log.info("方向题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("方向题生成失败，回退到默认问题: {}", e.getMessage(), e);
            return generateFallbackQuestions(skill, questionCount, difficultyBase);
        }
    }

    /**
     * 合并两组题单（简历题 + 方向题），后半段整体后移 offset 个索引。
     *
     * <p>纯函数（不依赖实例状态），故为 static 包级可见，便于直接单测。
     *
     * <p>必须走 {@code withIndex}：用顺序题单工厂 {@code create(...)} 重建会把
     * difficulty / followUpType / expectedPoints 丢掉，合并后自适应决策与轻量评估拿不到
     * 难度与考察要点（P4 待修正）。
     */
    static List<InterviewQuestionDTO> mergeQuestionBatches(
            List<InterviewQuestionDTO> first, List<InterviewQuestionDTO> second) {
        if (second.isEmpty()) {
            return InterviewQuestionIdentity.renumber(first);
        }
        if (first.isEmpty()) {
            return InterviewQuestionIdentity.renumber(second);
        }
        // P4-1：只重排展示顺序（questionIndex），标识与父链原样保留——
        // 旧实现必须同步偏移 parentQuestionIndex，漏一处就会串题；现在父链用标识表达，不受顺序影响
        List<InterviewQuestionDTO> merged = new ArrayList<>(first);
        merged.addAll(second);
        return InterviewQuestionIdentity.renumber(merged);
    }

    /**
     * 解析出题所用的 Skill。
     *
     * <p>focus 只对预设方向生效：JD 自定义方向的分类本身就是用户给定的考察范围，
     * 再叠加 focus 只会把范围收得更窄，语义上没有必要。
     */
    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText,
                                  List<String> focusCategories) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && customCategories != null && !customCategories.isEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
        }
        return skillService.focusOn(skillService.getSkill(skillId), focusCategories);
    }

    private String resolveDifficulty(String difficulty) {
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(
            difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
            DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY));
    }

    List<InterviewQuestionDTO> convertToQuestions(QuestionListDTO dto, int difficultyBase) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (dto == null || dto.questions() == null) {
            return questions;
        }

        for (QuestionDTO q : dto.questions()) {
            if (q == null || q.question() == null || q.question().isBlank()) {
                continue;
            }
            String type = (q.type() != null && !q.type().isBlank()) ? q.type().toUpperCase() : DEFAULT_QUESTION_TYPE;
            int difficulty = normalizeDifficulty(q.difficulty(), difficultyBase);
            List<String> expectedPoints = sanitizeExpectedPoints(q.expectedPoints());
            InterviewQuestionDTO main = InterviewQuestionDTO.createMain(
                index++, q.question(), type, q.category(), q.topicSummary(), difficulty, expectedPoints);
            questions.add(main);

            List<FollowUpDTO> followUps = sanitizeFollowUps(q.followUps());
            for (int i = 0; i < followUps.size(); i++) {
                FollowUpDTO followUp = followUps.get(i);
                // 追问不重复携带整体难度：决策引擎在 P4-3 依据主问题难度与作答质量决定是否加难
                // P4Q-6：category 保持主问题的稳定技能名（不再拼「（追问N）」），
                // 追问序号由 followUpIndex 独立表达——否则它会经 answers.category 变成画像伪技能
                questions.add(InterviewQuestionDTO.createFollowUp(
                    index++, followUp.question(), type,
                    q.category(), main.questionId(), i + 1,
                    normalizeFollowUpType(followUp.followUpType()),
                    sanitizeExpectedPoints(followUp.expectedPoints())
                ));
            }
        }

        return questions;
    }

    /** 难度归一：模型未给或越界时回落到整体难度基线，并夹取到 1-5 */
    private int normalizeDifficulty(Integer difficulty, int difficultyBase) {
        if (difficulty == null) {
            return difficultyBase;
        }
        return Math.max(1, Math.min(5, difficulty));
    }

    private List<String> sanitizeExpectedPoints(List<String> expectedPoints) {
        if (expectedPoints == null || expectedPoints.isEmpty()) {
            return List.of();
        }
        return expectedPoints.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .limit(6)
            .collect(Collectors.toList());
    }

    private List<FollowUpDTO> sanitizeFollowUps(List<FollowUpDTO> followUps) {
        // 候选池按「容量」截断（P4-4b）：素材可以多于运行期实际会问的条数
        if (followUpCandidateCount == 0 || followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
            .filter(item -> item != null && item.question() != null && !item.question().isBlank())
            .map(item -> new FollowUpDTO(item.question().trim(),
                normalizeFollowUpType(item.followUpType()),
                sanitizeExpectedPoints(item.expectedPoints())))
            .limit(followUpCandidateCount)
            .collect(Collectors.toList());
    }

    /** 追问语义类型白名单：未知类型回落为 DEPTH（继续深挖） */
    private String normalizeFollowUpType(String followUpType) {
        if (followUpType == null) {
            return InterviewQuestionDTO.FOLLOW_UP_DEPTH;
        }
        String upper = followUpType.trim().toUpperCase();
        return switch (upper) {
            case InterviewQuestionDTO.FOLLOW_UP_SCENARIO,
                 InterviewQuestionDTO.FOLLOW_UP_WHY,
                 InterviewQuestionDTO.FOLLOW_UP_CLARIFICATION,
                 InterviewQuestionDTO.FOLLOW_UP_TRADEOFF,
                 InterviewQuestionDTO.FOLLOW_UP_FAILURE,
                 InterviewQuestionDTO.FOLLOW_UP_CONTRIBUTION,
                 InterviewQuestionDTO.FOLLOW_UP_DEPTH -> upper;
            default -> InterviewQuestionDTO.FOLLOW_UP_DEPTH;
        };
    }

    /**
     * 将问题列表截断到指定的主问题数量（AI 多生时截断，少生时保留原样并记录警告）。
     */
    private List<InterviewQuestionDTO> capToMainCount(
            List<InterviewQuestionDTO> questions, int maxMainCount) {
        long currentMainCount = questions.stream().filter(q -> !q.isFollowUp()).count();

        if (currentMainCount <= maxMainCount) {
            if (currentMainCount < maxMainCount) {
                log.warn("AI 生成主问题不足: 请求={}, 实际={}", maxMainCount, currentMainCount);
            }
            return questions;
        }

        List<InterviewQuestionDTO> capped = new ArrayList<>();
        int mainSeen = 0;
        for (InterviewQuestionDTO q : questions) {
            if (!q.isFollowUp()) {
                mainSeen++;
            }
            if (mainSeen > maxMainCount) {
                break;
            }
            capped.add(q);
        }
        log.info("题目截断: 主问题 {} → {}", currentMainCount, maxMainCount);
        return capped;
    }

    private List<InterviewQuestionDTO> generateFallbackQuestions(SkillDTO skill, int count, int difficultyBase) {
        List<SkillCategoryDTO> categories = skill != null ? skill.categories() : List.of();
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        // fallback 主问题无考察要点可枚举，expectedPoints 留空；仍打上难度基线供决策引擎参考
        if (!categories.isEmpty()) {
            int generated = 0;
            while (generated < count) {
                SkillCategoryDTO cat = categories.get(generated % categories.size());
                String question = "请谈谈你在\"" + cat.label() + "\"方向的技术理解和实践经验。";
                InterviewQuestionDTO main = InterviewQuestionDTO.createMain(
                    index++, question, cat.key(), cat.label(), null, difficultyBase, List.of());
                questions.add(main);
                for (int j = 0; j < followUpCandidateCount; j++) {
                    questions.add(InterviewQuestionDTO.createFollowUp(
                        index++, buildDefaultFollowUp(question, j + 1),
                        cat.key(), cat.label(), main.questionId(), j + 1,
                        defaultFollowUpType(j),
                        List.of()
                    ));
                }
                generated++;
            }
            return questions;
        }

        for (int i = 0; i < Math.min(count, GENERIC_FALLBACK_QUESTIONS.length); i++) {
            String[] q = GENERIC_FALLBACK_QUESTIONS[i];
            InterviewQuestionDTO main = InterviewQuestionDTO.createMain(
                index++, q[0], q[1], q[2], null, difficultyBase, List.of());
            questions.add(main);
            for (int j = 0; j < followUpCandidateCount; j++) {
                questions.add(InterviewQuestionDTO.createFollowUp(
                    index++, buildDefaultFollowUp(q[0], j + 1),
                    q[1], q[2], main.questionId(), j + 1,
                    defaultFollowUpType(j),
                    List.of()
                ));
            }
        }
        return questions;
    }

    private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) {
            return "暂无历史提问";
        }

        Map<String, List<String>> grouped = new HashMap<>();
        for (HistoricalQuestion hq : historicalQuestions) {
            String type = hq.type() != null && !hq.type().isBlank() ? hq.type() : DEFAULT_QUESTION_TYPE;
            String summary = hq.topicSummary();
            if (summary == null || summary.isBlank()) {
                String q = hq.question();
                summary = q.length() > 30 ? q.substring(0, 30) + "…" : q;
            }
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(summary);
        }

        StringBuilder sb = new StringBuilder("已考过的知识点（避免重复出题）：\n");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ");
            sb.append(String.join(", ", entry.getValue()));
            sb.append('\n');
        }
        return sb.toString();
    }

    private String buildJdSection(String sourceJd) {
        if (sourceJd == null || sourceJd.isBlank()) {
            return "";
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
    }

    private String buildSkillPersonaSection(SkillDTO skill) {
        if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
            return "";
        }
        return "\n\n# Skill Persona\n"
            + "以下内容来自当前面试方向的 SKILL.md，请作为面试官角色、风格与出题约束：\n"
            + promptSanitizer.wrapWithDelimiters("skill_persona", skill.persona());
    }

    private String buildDefaultFollowUp(String mainQuestion, int order) {
        if (order == 1) {
            return "基于\"" + mainQuestion + "\"，请结合你亲自做过的一个真实场景展开说明。";
        }
        if (order == 2) {
            return "基于\"" + mainQuestion + "\"，如果线上出现异常，你会如何定位并给出修复方案？";
        }
        return "基于\"" + mainQuestion + "\"，当时为什么选这个方案而不是别的做法？";
    }

    /**
     * 默认追问的类型轮转（P4-4b）：让预置候选覆盖不同考察目的，
     * 而不是整组都落在 DEPTH。顺序：场景 → 故障 → 取舍 → 个人贡献 → 原理 → 深挖。
     */
    private String defaultFollowUpType(int zeroBasedOrder) {
        return switch (zeroBasedOrder % 6) {
            case 0 -> InterviewQuestionDTO.FOLLOW_UP_SCENARIO;
            case 1 -> InterviewQuestionDTO.FOLLOW_UP_FAILURE;
            case 2 -> InterviewQuestionDTO.FOLLOW_UP_TRADEOFF;
            case 3 -> InterviewQuestionDTO.FOLLOW_UP_CONTRIBUTION;
            case 4 -> InterviewQuestionDTO.FOLLOW_UP_WHY;
            default -> InterviewQuestionDTO.FOLLOW_UP_DEPTH;
        };
    }

    /** 运行期追问预算（P4-4b）：单组实际最多追问几条，供逐轮决策与上下文摘要使用 */
    public int getFollowUpBudget() {
        return followUpBudget;
    }

    /** 组内候选池容量（P4-4b）：供后台预备判断补题上限 */
    public int getFollowUpCandidateCount() {
        return followUpCandidateCount;
    }

    /** 后台预备阈值（P4-4b）：0 表示关闭后台预备 */
    public int getBackgroundPrepThreshold() {
        return backgroundPrepThreshold;
    }
}
