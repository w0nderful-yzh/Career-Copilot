package interview.guide.modules.interview.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 逐题轻量评估配置（P4-2）。
 * Prompt 模板路径遵循「prompts 统一放 resources/prompts」约定，可经 yml 覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.interview.turn-evaluation")
public class TurnEvaluationProperties {

    private String systemPromptPath = "classpath:prompts/turn-evaluation-system.st";
    private String userPromptPath = "classpath:prompts/turn-evaluation-user.st";
}
