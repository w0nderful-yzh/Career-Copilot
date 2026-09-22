"""简历 Gap / Patch 结构化输出的真实模型兼容边界。"""

import pytest
from pydantic import ValidationError

from career_copilot.schemas.resume_patch import ResumePatchProposal


def test_single_explanation_strings_are_normalized_to_lists():
    proposal = ResumePatchProposal.model_validate(
        {
            "summary": "按 JD 突出已有后端经历",
            "jdGapAnalysis": {
                "jobTitle": "Java 后端实习生",
                "matchLevel": "MEDIUM",
                "summary": "基础技术匹配，交付证据需补强",
                "items": [
                    {
                        "requirement": "熟悉 Spring Boot",
                        "status": "PARTIAL",
                        "resumeEvidence": "项目中使用 Spring Boot",
                        "impact": "影响岗位技术栈匹配",
                        "verificationRequired": "核实本人负责的接口范围",
                    }
                ],
            },
            "patches": [
                {
                    "id": "patch_1",
                    "type": "REPLACE",
                    "path": "projects[0].bullets[0]",
                    "oldValue": "使用 Spring Boot 开发",
                    "newValue": "基于 Spring Boot 实现后端接口",
                    "reason": "明确技术动作",
                    "evidence": "原文已有 Spring Boot",
                    "impact": "项目经历首条",
                    "verificationRequired": "核实接口职责",
                }
            ],
        }
    )

    assert proposal.jdGapAnalysis is not None
    assert proposal.jdGapAnalysis.items[0].resumeEvidence == ["项目中使用 Spring Boot"]
    assert proposal.jdGapAnalysis.items[0].verificationRequired == [
        "核实本人负责的接口范围"
    ]
    assert proposal.patches[0].evidence == ["原文已有 Spring Boot"]
    assert proposal.patches[0].verificationRequired == ["核实接口职责"]


def test_jd_gap_rejects_empty_items_and_missing_verification_facts():
    with pytest.raises(ValidationError):
        ResumePatchProposal.model_validate(
            {
                "summary": "没有形成有效对照",
                "jdGapAnalysis": {
                    "jobTitle": "Java 后端实习生",
                    "matchLevel": "UNKNOWN",
                    "summary": "没有形成有效对照",
                    "items": [],
                },
                "patches": [],
            }
        )

    with pytest.raises(ValidationError):
        ResumePatchProposal.model_validate(
            {
                "summary": "数据库经验缺少证据",
                "jdGapAnalysis": {
                    "jobTitle": "Java 后端实习生",
                    "matchLevel": "LOW",
                    "summary": "数据库经验缺少证据",
                    "items": [
                        {
                            "requirement": "熟悉 MySQL",
                            "status": "MISSING",
                            "resumeEvidence": [],
                            "impact": "影响数据库能力判断",
                            "verificationRequired": [],
                        }
                    ],
                },
                "patches": [],
            }
        )
