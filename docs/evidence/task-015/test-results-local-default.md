# 로컬 기본 실행 결과 (./gradlew clean test --offline --no-daemon, CLI 실행 테스트는 명시적 skip)

JUnit XML(`build/test-results/test`)에서 생성. 합계: 총 181건, 통과 175, 실패 0, 오류 0, skip 6.

| 테스트 클래스 | 총 | skip | 실패 | 오류 |
|---|---|---|---|---|
| com.trustagent.core.CoreApplicationIntegrationTest | 7 | 0 | 0 | 0 |
| com.trustagent.core.CoreEndToEndFlowIntegrationTest | 5 | 0 | 0 | 0 |
| com.trustagent.core.bootstrap.AppendOnlyBootstrapChecksTest | 3 | 0 | 0 | 0 |
| com.trustagent.core.config.DemoFeatureProductionGuardTest | 8 | 0 | 0 | 0 |
| com.trustagent.core.config.RuntimeDatasourcePropertiesTest | 6 | 0 | 0 | 0 |
| com.trustagent.core.database.PublicProductSchemaIntegrationTest | 9 | 0 | 0 | 0 |
| com.trustagent.core.database.SyntheticInternalSchemaIntegrationTest | 7 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.BusinessTimePolicyTest | 1 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.InternalChecklistUsePolicyTest | 1 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporterIntegrationTest | 8 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.proposal.ChecklistChangeProposalGeneratorTest | 3 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.proposal.ChecklistProposalIntegrationTest | 11 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.proposal.HumanReviewIntegrationTest | 7 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.proposal.ProposalValidationIntegrationTest | 10 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.proposal.ProposalValidatorTest | 9 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.query.HumanReviewApplicableIntegrationTest | 3 | 0 | 0 | 0 |
| com.trustagent.core.internalpolicy.query.InternalPolicyApplicableIntegrationTest | 20 | 0 | 0 | 0 |
| com.trustagent.core.json.CanonicalJsonHasherTest | 2 | 0 | 0 | 0 |
| com.trustagent.core.preparation.AiServicePreparationIntegrationTest | 5 | 5 | 0 | 0 |
| com.trustagent.core.preparation.ConsultationPreparationHashTest | 2 | 0 | 0 | 0 |
| com.trustagent.core.preparation.ConsultationPreparationIntegrationTest | 13 | 0 | 0 | 0 |
| com.trustagent.core.preparation.ConsultationPreparationReadyIntegrationTest | 3 | 1 | 0 | 0 |
| com.trustagent.core.publicproduct.baseline.BaselineImportCommandIntegrationTest | 1 | 0 | 0 | 0 |
| com.trustagent.core.publicproduct.baseline.BaselineImportDatasourceConfigurationTest | 2 | 0 | 0 | 0 |
| com.trustagent.core.publicproduct.baseline.BaselineImporterIntegrationTest | 13 | 0 | 0 | 0 |
| com.trustagent.core.publicproduct.query.PublicEvidenceConfirmationPolicyTest | 2 | 0 | 0 | 0 |
| com.trustagent.core.publicproduct.query.PublicEvidencePolicyPropertiesTest | 1 | 0 | 0 | 0 |
| com.trustagent.core.publicproduct.query.PublicProductObservedStateIntegrationTest | 6 | 0 | 0 | 0 |
| com.trustagent.core.publicproduct.query.PublicProductObservedStateServiceTest | 2 | 0 | 0 | 0 |
| com.trustagent.core.publicproduct.query.PublicProductQueryExceptionHandlerTest | 2 | 0 | 0 | 0 |
| com.trustagent.core.tool.ToolApiIntegrationTest | 7 | 0 | 0 | 0 |
| com.trustagent.core.tool.ToolApiMissingTokenIntegrationTest | 1 | 0 | 0 | 0 |
| com.trustagent.core.web.RequestTraceFilterTest | 1 | 0 | 0 | 0 |

## skip된 테스트(명시적 skip)

- com.trustagent.core.preparation.AiServicePreparationIntegrationTest: prepareCommandAssemblesPartialPreparationAndRecordsIt()
- com.trustagent.core.preparation.AiServicePreparationIntegrationTest: rerunRechecksCoreAndRecordsOnlyOnce()
- com.trustagent.core.preparation.AiServicePreparationIntegrationTest: stateChangeProducesNewHoldPreparation()
- com.trustagent.core.preparation.AiServicePreparationIntegrationTest: withdrawalAfterRecordingProducesNewHoldPreparation()
- com.trustagent.core.preparation.AiServicePreparationIntegrationTest: wrongRecordTokenLeavesPreparationUnrecorded()
- com.trustagent.core.preparation.ConsultationPreparationReadyIntegrationTest: prepareCommandProducesFullyReadyPreparation()
