package interview.guide.modules.interview.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewQuestionProperties {

    /**
     * 运行期追问预算（P4-4b）：单个话题组实际最多追问几条，与候选池容量解耦。
     * 兼容旧配置项 {@code followUpCount}，语义从「池容量」收窄为「预算」。
     */
    private int followUpCount = 2;

    /**
     * 组内候选池容量（P4-4b）：每个主问题预置多少条追问素材。
     * 候选容量不等于实际追问预算——池是素材，预算是消费上限。
     */
    private int followUpCandidateCount = 3;

    /**
     * 后台预备候选阈值（P4-4b）：某话题组剩余未问追问低于该值时，投递后台补题任务。
     * 0 表示关闭后台预备（实时路径仍由同轮受限生成兜底）。
     */
    private int backgroundPrepThreshold = 1;

    private String questionSystemPromptPath = "classpath:prompts/interview-question-skill-system.st";
    private String questionUserPromptPath = "classpath:prompts/interview-question-skill-user.st";
    private String resumeQuestionSystemPromptPath = "classpath:prompts/interview-question-resume-system.st";
    private String resumeQuestionUserPromptPath = "classpath:prompts/interview-question-resume-user.st";

    /** 后台预备追问的系统提示词（P4-4b） */
    private String candidatePrepSystemPromptPath = "classpath:prompts/interview-candidate-prep-system.st";

    /** 后台预备追问的用户提示词（P4-4b） */
    private String candidatePrepUserPromptPath = "classpath:prompts/interview-candidate-prep-user.st";
}
