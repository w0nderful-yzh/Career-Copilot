package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeOptimizationProposalEntity;
import interview.guide.modules.resume.model.ResumeVersionEntity;
import interview.guide.modules.resume.model.ResumePatchItem;
import interview.guide.modules.resume.repository.ResumeVersionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Patch 应用服务（P2-2 的 apply_resume_patches Tool 支撑）：把用户确认的
 * Patch 按.JSON path 应用到源版本内容上，生成新版本（source=AI_OPTIMIZE）。
 *
 * <p>安全边界：
 * - oldValue 一致性校验：目标位置当前值与提案时不一致 → 拒绝（内容已漂移）
 * - path 白名单：只允许已知结构段，拒绝任意 JSON 改写
 * - 原版本永不修改：新版本 append（version+1）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePatchApplyService {

  private final ResumeVersionRepository versionRepository;
  private final ResumeVersionService versionService;
  private final ResumeOptimizationProposalService proposalService;
  private final ObjectMapper objectMapper;

  private static final Set<String> KNOWN_SEGMENTS = Set.of(
      "basicInfo", "education", "experience", "projects", "skills", "customSections");

  /**
   * 应用提案中的选中 Patch，生成新版本。
   *
   * @param patchIds 用户勾选的 patch id；空表示全部应用
   * @return 新创建的版本（AI_OPTIMIZE）
   */
  @Transactional(rollbackFor = Exception.class)
  public ResumeVersionEntity applyPatches(Long proposalId, List<String> patchIds) {
    var proposal = proposalService.getProposal(proposalId);
    List<ResumePatchItem> allPatches = proposalService.parsePatches(proposal);

    List<ResumePatchItem> selected = (patchIds == null || patchIds.isEmpty())
        ? allPatches
        : allPatches.stream().filter(p -> patchIds.contains(p.id())).toList();
    if (selected.isEmpty()) {
      throw new BusinessException(
          ErrorCode.RESUME_OPTIMIZATION_INVALID, "没有可应用的修改建议");
    }

    // 1. 读源版本内容
    ResumeVersionEntity sourceVersion = versionService.getVersion(proposal.getSourceVersionId());
    JsonNode content;
    try {
      content = objectMapper.readTree(sourceVersion.getContentJson());
    } catch (JacksonException e) {
      log.error("源版本内容解析失败: versionId={}", sourceVersion.getId(), e);
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "源版本内容损坏");
    }
    if (!(content instanceof ObjectNode root)) {
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "源版本内容格式异常");
    }

    // 2. 逐条应用（oldValue 一致性校验失败 → 整体失败，不留半应用状态）
    for (ResumePatchItem patch : selected) {
      applySingle(root, patch);
    }

    // 3. 提案状态流转 + 新版本落库（同事务，任一失败整体回滚）
    String newContentJson;
    try {
      newContentJson = objectMapper.writeValueAsString(root);
    } catch (JacksonException e) {
      log.error("序列化新版本内容失败", e);
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "保存新版本失败");
    }
    ResumeVersionEntity newVersion = createNewVersion(sourceVersion, newContentJson);
    proposalService.transitionFromPending(
        proposalId, ResumeOptimizationProposalEntity.ProposalStatus.APPLIED);
    log.info("优化提案已应用: proposalId={}, 新版本 resumeId={} v{}", proposalId,
        newVersion.getResumeId(), newVersion.getVersion());
    return newVersion;
  }

  private void applySingle(ObjectNode root, ResumePatchItem patch) {
    PointerTarget target = resolvePointer(root, patch.path());

    switch (patch.type()) {
      case REPLACE -> {
        String current = textValue(target.node());
        if (!normalized(current).equals(normalized(patch.oldValue()))) {
          throw new BusinessException(
              ErrorCode.RESUME_OPTIMIZATION_PATCH_CONFLICT,
              "简历内容已变化（" + patch.path() + " 与提案时不一致），请重新生成优化建议");
        }
        target.replace(patch.newValue());
      }
      case DELETE -> {
        String current = textValue(target.node());
        if (patch.oldValue() != null && !patch.oldValue().isBlank()
            && !normalized(current).equals(normalized(patch.oldValue()))) {
          throw new BusinessException(
              ErrorCode.RESUME_OPTIMIZATION_PATCH_CONFLICT,
              "简历内容已变化（" + patch.path() + "），请重新生成优化建议");
        }
        target.remove();
      }
      case ADD -> {
        // ADD 语义：向 path 指向的数组追加 newValue；path 指向容器字段时追加新项
        target.add(patch.newValue());
      }
      case REORDER -> throw new BusinessException(
          ErrorCode.RESUME_OPTIMIZATION_INVALID, "暂不支持 REORDER 类型的修改");
    }
  }

  /** JSON Pointer 解析：仅支持已知顶层段 + 数组索引 + 字段链（如 projects[0].bullets[1]） */
  private PointerTarget resolvePointer(ObjectNode root, String path) {
    if (path == null || path.isBlank()) {
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 为空");
    }
    String[] tokens = path.split("(?=[\\.\\[])");
    String topSegment = tokens[0];
    if (!KNOWN_SEGMENTS.contains(topSegment)) {
      throw new BusinessException(
          ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 非法: " + path);
    }

    JsonNode current = root;
    JsonNode parent = null;
    String lastField = topSegment;
    Integer lastIndex = null;

    current = current.get(topSegment);
    if (current == null) {
      throw new BusinessException(
          ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 不存在: " + path);
    }
    for (int i = 1; i < tokens.length; i++) {
      String token = tokens[i].trim();
      parent = current;
      if (token.startsWith("[")) {
        String indexText = token.substring(1, token.length() - 1);
        int index;
        try {
          index = Integer.parseInt(indexText);
        } catch (NumberFormatException e) {
          throw new BusinessException(
              ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 数组索引非法: " + path);
        }
        if (!current.isArray() || index < 0 || index >= current.size()) {
          throw new BusinessException(
              ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 越界: " + path);
        }
        lastIndex = index;
        lastField = null;
        current = current.get(index);
      } else {
        String field = token.startsWith(".") ? token.substring(1) : token;
        if (field.isBlank() || !(current instanceof ObjectNode object)) {
          throw new BusinessException(
              ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 非法: " + path);
        }
        lastIndex = null;
        lastField = field;
        current = object.get(field);
        if (current == null) {
          throw new BusinessException(
              ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 不存在: " + path);
        }
      }
    }
    return new PointerTarget(root, parent, current, lastField, lastIndex, path);
  }

  private static String textValue(JsonNode node) {
    return node != null && node.isString() ? node.stringValue() : "";
  }

  /** 一致性比较的轻量归一化：trim（不忽略空白差异以外的内容，避免误放行） */
  private static String normalized(String value) {
    return value == null ? "" : value.trim();
  }

  /** 在源版本之后追加新版本（version+1，AI_OPTIMIZE） */
  private ResumeVersionEntity createNewVersion(ResumeVersionEntity source, String contentJson) {
    int nextVersion = source.getVersion() + 1;
    // 并发保护：同版本号已存在（极小概率并发应用）→ 明确报错
    versionRepository.findByResumeIdAndVersion(source.getResumeId(), nextVersion)
        .ifPresent(existing -> {
          throw new BusinessException(
              ErrorCode.RESUME_OPTIMIZATION_INVALID, "新版本号冲突，请重试");
        });

    ResumeVersionEntity created = new ResumeVersionEntity();
    created.setResumeId(source.getResumeId());
    created.setVersion(nextVersion);
    created.setSourceVersionId(source.getId());
    created.setOptimizationType("GENERAL");
    created.setSource(ResumeVersionEntity.VersionSource.AI_OPTIMIZE);
    created.setConfirmationStatus(ResumeVersionEntity.ConfirmationStatus.ACTIVE);
    created.setContentJson(contentJson);
    created.setSourceCreatedAt(source.getCreatedAt());
    return versionRepository.save(created);
  }

  /** JSON path 定位结果：parent + 定位字段/索引，支持 replace/remove/add */
  private record PointerTarget(
      ObjectNode root,
      JsonNode parent,
      JsonNode node,
      String field,
      Integer index,
      String path
  ) {

    void replace(String newValue) {
      if (field != null && parent instanceof ObjectNode object) {
        object.put(field, newValue);
      } else if (index != null && parent instanceof ArrayNode array) {
        array.set(index, newValue);
      } else {
        throw new BusinessException(
            ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 不支持替换: " + path);
      }
    }

    void remove() {
      if (field != null && parent instanceof ObjectNode object) {
        object.remove(field);
      } else if (index != null && parent instanceof ArrayNode array) {
        array.remove(index);
      } else {
        throw new BusinessException(
            ErrorCode.RESUME_OPTIMIZATION_INVALID, "patch path 不支持删除: " + path);
      }
    }

    void add(String newValue) {
      // ADD 目标必须是数组（bullets / education / projects 等）
      if (node instanceof ArrayNode array) {
        array.add(newValue);
      } else {
        throw new BusinessException(
            ErrorCode.RESUME_OPTIMIZATION_INVALID,
            "ADD 只支持向数组追加（" + path + " 不是数组）");
      }
    }
  }
}
