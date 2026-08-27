package interview.guide.modules.agenttool.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.agenttool.dto.ToolInfoDTO;
import interview.guide.modules.agenttool.dto.ToolResponse;
import interview.guide.modules.agenttool.model.AgentToolPermission;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.resume.model.ResumeContentDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolServiceTest {

  @Mock
  private ResumeHistoryService resumeHistoryService;
  @Mock
  private ResumePersistenceService resumePersistenceService;
  @Mock
  private InterviewPersistenceService interviewPersistenceService;
  @Mock
  private InterviewHistoryService interviewHistoryService;
  @Mock
  private KnowledgeBaseListService knowledgeBaseListService;
  @Mock
  private KnowledgeBaseQueryService knowledgeBaseQueryService;
  @Mock
  private InterviewSkillService interviewSkillService;

  @InjectMocks
  private AgentToolService agentToolService;

  @Nested
  @DisplayName("Tool 注册表")
  class ToolRegistry {

    @Test
    @DisplayName("listTools 返回全部 Tool 且均为 READ 权限")
    void listToolsReturnsAllReadOnlyTools() {
      List<ToolInfoDTO> tools = agentToolService.listTools();

      assertThat(tools).hasSize(8);
      assertThat(tools)
          .extracting(ToolInfoDTO::permission)
          .containsOnly(AgentToolPermission.READ);
      assertThat(tools)
          .extracting(ToolInfoDTO::name)
          .contains("get_resume_list", "search_knowledge");
      assertThat(tools)
          .allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotBlank();
          });
    }

    @Test
    @DisplayName("执行未知 Tool 抛出 AGENT_TOOL_NOT_FOUND")
    void executeUnknownToolFails() {
      assertThatThrownBy(() -> agentToolService.execute("unknown_tool", Map.of()))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.AGENT_TOOL_NOT_FOUND.getCode());
    }
  }

  @Nested
  @DisplayName("简历 Tool")
  class ResumeTools {

    @Test
    @DisplayName("get_resume_list 返回简历列表")
    void getResumeListDelegates() {
      when(resumeHistoryService.getAllResumes()).thenReturn(List.of());

      ToolResponse response = agentToolService.execute("get_resume_list", Map.of());

      assertThat(response.tool()).isEqualTo("get_resume_list");
      verify(resumeHistoryService).getAllResumes();
    }

    @Test
    @DisplayName("get_resume_analysis 缺少 resumeId 抛出参数错误")
    void getResumeAnalysisMissingArgumentFails() {
      assertThatThrownBy(() -> agentToolService.execute("get_resume_analysis", Map.of()))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.AGENT_TOOL_ARGUMENT_INVALID.getCode());
      verify(resumePersistenceService, never()).getLatestAnalysisAsDTO(anyLong());
    }

    @Test
    @DisplayName("get_resume_analysis 委托查询最新分析结果")
    void getResumeAnalysisDelegates() {
      ResumeAnalysisResponse analysis = mock(ResumeAnalysisResponse.class);
      when(resumePersistenceService.getLatestAnalysisAsDTO(102L))
          .thenReturn(Optional.of(analysis));

      ToolResponse response = agentToolService.execute(
          "get_resume_analysis", Map.of("resumeId", 102));

      assertThat(response.tool()).isEqualTo("get_resume_analysis");
      assertThat(response.data()).isSameAs(analysis);
      verify(resumePersistenceService).getLatestAnalysisAsDTO(102L);
    }

    @Test
    @DisplayName("get_resume 返回完整简历文本与元信息")
    void getResumeReturnsContent() {
      ResumeEntity resume = new ResumeEntity();
      resume.setId(103L);
      resume.setOriginalFilename("resume.pdf");
      resume.setResumeText("姓名：张三\n项目经历：基于 LangGraph 的 Agent 平台");
      resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
      when(resumePersistenceService.findById(103L)).thenReturn(Optional.of(resume));

      ToolResponse response = agentToolService.execute(
          "get_resume", Map.of("resumeId", 103));

      assertThat(response.tool()).isEqualTo("get_resume");
      ResumeContentDTO content = (ResumeContentDTO) response.data();
      assertThat(content.filename()).isEqualTo("resume.pdf");
      assertThat(content.resumeText()).contains("基于 LangGraph 的 Agent 平台");
      assertThat(content.analyzeStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("get_resume 按 maxChars 服务端截断")
    void getResumeTruncatesByMaxChars() {
      ResumeEntity resume = new ResumeEntity();
      resume.setId(104L);
      resume.setOriginalFilename("resume.pdf");
      resume.setResumeText("一二三四五六七八九十");
      when(resumePersistenceService.findById(104L)).thenReturn(Optional.of(resume));

      ToolResponse response = agentToolService.execute(
          "get_resume", Map.of("resumeId", 104, "maxChars", 4));

      ResumeContentDTO content = (ResumeContentDTO) response.data();
      assertThat(content.resumeText()).isEqualTo("一二三四");
    }

    @Test
    @DisplayName("get_resume 简历不存在抛 RESUME_NOT_FOUND")
    void getResumeNotFoundFails() {
      when(resumePersistenceService.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> agentToolService.execute(
          "get_resume", Map.of("resumeId", 999)))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.RESUME_NOT_FOUND.getCode());
    }
  }

  @Nested
  @DisplayName("面试 Tool")
  class InterviewTools {

    @Test
    @DisplayName("get_interview_history 无 resumeId 时返回全部会话")
    void getInterviewHistoryWithoutResumeId() {
      when(interviewPersistenceService.findAll()).thenReturn(List.of());

      agentToolService.execute("get_interview_history", Map.of());

      verify(interviewPersistenceService).findAll();
      verify(interviewPersistenceService, never()).findByResumeId(anyLong());
    }

    @Test
    @DisplayName("get_interview_history 带 resumeId 时按简历过滤")
    void getInterviewHistoryWithResumeId() {
      when(interviewPersistenceService.findByResumeId(7L)).thenReturn(List.of());

      agentToolService.execute("get_interview_history", Map.of("resumeId", 7));

      verify(interviewPersistenceService).findByResumeId(7L);
      verify(interviewPersistenceService, never()).findAll();
    }

    @Test
    @DisplayName("get_interview_report 委托查询面试详情")
    void getInterviewReportDelegates() {
      when(interviewHistoryService.getInterviewDetail("sess-1"))
          .thenReturn(mock(InterviewDetailDTO.class));

      ToolResponse response = agentToolService.execute(
          "get_interview_report", Map.of("sessionId", "sess-1"));

      assertThat(response.tool()).isEqualTo("get_interview_report");
      verify(interviewHistoryService).getInterviewDetail("sess-1");
    }
  }

  @Nested
  @DisplayName("知识库 Tool")
  class KnowledgeTools {

    @Test
    @DisplayName("search_knowledge 转换参数并委托 RAG 查询")
    void searchKnowledgeDelegates() {
      QueryResponse expected = new QueryResponse("answer", 1L, "知识库");
      when(knowledgeBaseQueryService.queryKnowledgeBase(any(QueryRequest.class)))
          .thenReturn(expected);

      ToolResponse response = agentToolService.execute(
          "search_knowledge",
          Map.of("knowledgeBaseIds", List.of(1, 2), "question", "JVM GC 是什么？"));

      assertThat(response.data()).isSameAs(expected);
      verify(knowledgeBaseQueryService).queryKnowledgeBase(
          new QueryRequest(List.of(1L, 2L), "JVM GC 是什么？"));
    }

    @Test
    @DisplayName("search_knowledge 缺少 question 抛出参数错误")
    void searchKnowledgeMissingQuestionFails() {
      assertThatThrownBy(() -> agentToolService.execute(
          "search_knowledge", Map.of("knowledgeBaseIds", List.of(1))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.AGENT_TOOL_ARGUMENT_INVALID.getCode());
    }

    @Test
    @DisplayName("list_knowledge_bases 返回知识库列表")
    void listKnowledgeBasesDelegates() {
      agentToolService.execute("list_knowledge_bases", Map.of());

      verify(knowledgeBaseListService).listKnowledgeBases();
    }
  }
}