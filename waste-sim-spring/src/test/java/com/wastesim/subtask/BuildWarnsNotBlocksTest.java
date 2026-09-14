package com.wastesim.subtask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Regression: the former warning-only build must now fail closed. */
class BuildWarnsNotBlocksTest {
 private final SubtaskSessionService sessions = SubtaskTestSupport.service();
 @Test void unresolvedTrafficBlocksBuildAndCanBeAnsweredAgain() {
  SubtaskTestSupport.answerEverything(sessions, "k");
  String mode = SubtaskTestSupport.idOfField(sessions, "k", "trafficMode");
  sessions.submit("k", mode, "NONE", null);
  sessions.submit("k", mode, "APPLY", null);
  var build = sessions.build("k");
  assertFalse(build.ok());
  assertTrue(build.message().contains("trafficProfileId"), build.message());
  assertNull(sessions.activeSession("k").spec());
  assertEquals("trafficProfileId", sessions.currentStep("k").question().answerField());
  sessions.submit("k", SubtaskTestSupport.idOfField(sessions, "k", "trafficProfileId"), "jangryang-weekday", null);
  var repaired = sessions.build("k");
  assertTrue(repaired.ok(), repaired.message());
 }
 @Test void normalConfigurationBuilds() {
  SubtaskTestSupport.answerEverything(sessions, "k");
  assertEquals(SubtaskState.READY, sessions.activeSession("k").state());
  var result = sessions.build("k");
  assertTrue(result.ok(), result.message());
 }
}
