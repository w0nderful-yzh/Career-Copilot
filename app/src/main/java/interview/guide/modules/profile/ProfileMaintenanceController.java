package interview.guide.modules.profile;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.profile.service.SkillProfileRepairService;
import interview.guide.modules.profile.service.SkillProfileRepairService.RepairReport;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画像维护入口（P4Q-6）。
 *
 * <p>只暴露"可重跑"的修复动作，不提供任何改写分数的手段——画像分始终由证据聚合得出。
 * 修复是幂等的：第二次调用不会产生任何变更，也不会重复计分。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "能力画像维护", description = "历史数据修复（幂等，可重跑）")
public class ProfileMaintenanceController {

  private final SkillProfileRepairService repairService;

  /**
   * 合并「追问序号拼进技能名」的历史伪技能证据并重算画像。
   *
   * <p>对应 P4Q-6 的「清理已有伪技能证据并重算画像」；返回实际改动条数供核对。
   */
  @PostMapping("/api/profile/repair/follow-up-skills")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
  public Result<RepairReport> repairFollowUpSkills() {
    RepairReport report = repairService.mergeFollowUpPseudoSkills();
    log.info("画像追问技能修复: {}", report);
    return Result.success(report);
  }
}
