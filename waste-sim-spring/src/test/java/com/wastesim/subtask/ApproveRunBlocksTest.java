package com.wastesim.subtask;
import com.wastesim.ledger.*;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ApproveRunBlocksTest {
 private final SubtaskSessionService sessions = SubtaskTestSupport.service();
 private JangnyangSubtaskSession built() {
  SubtaskTestSupport.answerEverything(sessions, "k");
  var result = sessions.build("k");
  assertTrue(result.ok(), result.message());
  return sessions.activeSession("k");
 }
 @Test void blockedLedgerCannotRunEvenAfterBuild() {
  var session = built();
  String id = JangnyangLedgerWiring.parameterIdOf("trafficProfileId");
  session.ledger().append(new ParameterDecision(session.ledger().nextDecisionId(id), id,
   DecisionState.STALE, null, null, null, null, null, null, List.of(), "source_expired", "expiry", Instant.now()));
  var approval = sessions.approveRunChecked("k");
  assertFalse(approval.approved());
  assertTrue(approval.blocks().stream().anyMatch(b -> b.contains("trafficProfileId")));
  assertEquals(SubtaskState.BUILT, session.state());
 }
 @Test void configurationTamperingAfterPreviewCannotRun() {
  var session = built();
  session.spec().toSimulationConfig().setDays(99);
  assertFalse(sessions.approveRunChecked("k").approved());
  assertEquals(SubtaskState.BUILT, session.state());
 }
 @Test void editingBuiltAnswerInvalidatesPreviewAndRequiresRebuild() {
  var session = built();
  sessions.submit("k", SubtaskTestSupport.idOfField(sessions, "k", "days"), 7, null);
  assertNull(session.spec());
  assertNull(sessions.approveRun("k"));
  assertTrue(sessions.build("k").ok());
  assertEquals(7, sessions.approveRun("k").toSimulationConfig().getDays());
 }
 @Test void invalidReplacementCannotRetainOldDecisionOrPreview() {
  var session = built();
  sessions.submit("k", SubtaskTestSupport.idOfField(sessions, "k", "days"), -1, null);
  assertNull(session.spec());
  assertFalse(sessions.build("k").ok());
  assertNull(sessions.approveRun("k"));
  assertEquals("days", sessions.currentStep("k").question().answerField());
 }
 @Test void normalConfigurationRuns() { built(); assertNotNull(sessions.approveRun("k")); }
 @Test void collectingCannotRun() { sessions.start("k"); assertNull(sessions.approveRun("k")); }
}
