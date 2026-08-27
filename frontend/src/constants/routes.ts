export const ROUTES = {
  copilot: '/copilot',
  interview: '/interview',
  interviewHub: '/interview-hub',
  interviewHistory: '/interviews',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  resumeLibrary: '/history',
  resumeDetail: (resumeId: number) => `/history/${resumeId}`,
  knowledgeBase: '/knowledgebase',
  knowledgeChat: '/knowledgebase/chat',
  knowledgebaseUpload: '/knowledgebase/upload',
  settings: '/settings',
} as const;

interface ActionRouteTarget {
  label: string;
  buildPath: (params?: Record<string, unknown>) => string | null;
}

function staticPath(path: string): ActionRouteTarget['buildPath'] {
  return () => path;
}

function readPositiveInteger(value: unknown): number | null {
  const number = typeof value === 'string' && value.trim() !== '' ? Number(value) : value;
  return typeof number === 'number' && Number.isInteger(number) && number > 0 ? number : null;
}

// Agent 只能返回路由 key 和受控参数，任意 URL 或非法参数均不会导航。
export const ACTION_ROUTE_MAP: Record<string, ActionRouteTarget> = {
  RESUME_UPLOAD: { label: '上传简历', buildPath: staticPath(ROUTES.resumeUpload) },
  RESUME_LIBRARY: { label: '简历库', buildPath: staticPath(ROUTES.resumeLibrary) },
  RESUME_DETAIL: {
    label: '查看简历分析',
    buildPath: (params) => {
      const resumeId = readPositiveInteger(params?.resumeId);
      return resumeId === null ? null : ROUTES.resumeDetail(resumeId);
    },
  },
  INTERVIEW_CREATE: { label: '开始模拟面试', buildPath: staticPath(ROUTES.interviewHub) },
  INTERVIEW_HISTORY: { label: '面试记录', buildPath: staticPath(ROUTES.interviewHistory) },
  KNOWLEDGE_BASE: { label: '知识库管理', buildPath: staticPath(ROUTES.knowledgeBase) },
  KNOWLEDGE_CHAT: { label: '问答助手', buildPath: staticPath(ROUTES.knowledgeChat) },
  SETTINGS: { label: '设置', buildPath: staticPath(ROUTES.settings) },
};

export function resolveActionRoute(
  route: string,
  params?: Record<string, unknown>,
): { path: string; label: string } | null {
  const target = ACTION_ROUTE_MAP[route];
  if (!target) return null;
  const path = target.buildPath(params);
  return path ? { path, label: target.label } : null;
}

export const ROUTE_PATTERNS = {
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
} as const;
