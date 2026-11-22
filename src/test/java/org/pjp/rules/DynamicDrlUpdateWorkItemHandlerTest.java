package org.pjp.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;

import org.drools.core.process.instance.impl.WorkItemImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.process.WorkItemManager;

/**
 * Unit test suite for {@link DynamicDrlUpdateWorkItemHandler}.
 *
 * <p>Validates the in-memory KJAR rebuild and live {@link org.kie.api.runtime.KieContainer}
 * hot-swap lifecycle without requiring a running jBPM/KIE Server instance. Before each test,
 * an empty baseline KJAR ({@code org.pjp:dynamic-rules-test:1.0.0}) is compiled into a fresh
 * {@code KieContainer} and injected into the handler under test.</p>
 *
 * <h2>Covered Scenarios</h2>
 * <ul>
 *   <li><b>Successful Hot-Swap:</b> Injects a valid DRL rule at runtime, verifies the
 *       {@link org.kie.api.builder.ReleaseId} version is incremented, confirms work item completion,
 *       and asserts that newly spawned {@link org.kie.api.runtime.KieSession} instances fire the
 *       updated rule.</li>
 *   <li><b>Compilation Failure Handling:</b> Supplies syntactically invalid DRL content and verifies
 *       that a {@link RuntimeException} detailing the DRL compilation errors is thrown while leaving
 *       the work item uncompleted.</li>
 * </ul>
 *
 * @author pjp
 * @see DynamicDrlUpdateWorkItemHandler
 */
class DynamicDrlUpdateWorkItemHandlerTest {

    private KieServices kieServices;
    private KieContainer kieContainer;
    private ReleaseId initialReleaseId;
    private DynamicDrlUpdateWorkItemHandler handler;

    /**
     * Initializes a clean, empty in-memory KJAR and {@link KieContainer} before each test execution,
     * then instantiates a fresh {@link DynamicDrlUpdateWorkItemHandler} bound to that container.
     */
    @BeforeEach
    void setUp() {
        kieServices = KieServices.Factory.get();
        initialReleaseId = kieServices.newReleaseId("org.pjp", "dynamic-rules-test", "1.0.0");

        // 1. Build an initial empty KJAR in memory so we have a valid KieContainer to inject
        KieFileSystem kfs = kieServices.newKieFileSystem();
        kfs.generateAndWritePomXML(initialReleaseId);

        KieBuilder kieBuilder = kieServices.newKieBuilder(kfs).buildAll();
        assertFalse(kieBuilder.getResults().hasMessages(org.kie.api.builder.Message.Level.ERROR));

        kieContainer = kieServices.newKieContainer(initialReleaseId);

        // 2. Inject the KieContainer into the handler
        handler = new DynamicDrlUpdateWorkItemHandler(kieContainer);
    }

    /**
     * Verifies that executing {@link DynamicDrlUpdateWorkItemHandler#executeWorkItem} with valid
     * {@code DrlContent} and {@code RuleFileName} parameters compiles the new rule, increments
     * the container's {@link ReleaseId} version, completes the work item, and makes the rule
     * immediately active for subsequent {@link KieSession} instances.
     */
    @Test
    void testExecuteWorkItem_UpdatesContainerAndFiresTrivialRule() {
        // Arrange: Define the trivial DRL
        String trivialDrl =
            "package org.pjp.rules;\n\n" +
            "rule \"Trivial Rule\"\n" +
            "    dialect \"mvel\"\n" +
            "    no-loop true\n" +
            "    when\n" +
            "        // anything\n" +
            "    then\n" +
            "        System.out.println(\"Rule Fired!\");\n" +
            "end";

        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setId(1L);
        Map<String, Object> params = new HashMap<>();
        params.put("DrlContent", trivialDrl);
        params.put("RuleFileName", "trivial.drl");
        workItem.setParameters(params);

        WorkItemManager workItemManager = mock(WorkItemManager.class);

        // Verify 0 rules fire before the update
        KieSession initialSession = kieContainer.newKieSession();
        assertEquals(0, initialSession.fireAllRules(), "No rules should exist prior to update");
        initialSession.dispose();

        // Act: Run the WorkItemHandler
        handler.executeWorkItem(workItem, workItemManager);

        // Assert: Verify WorkItem completed and ReleaseId version incremented
        verify(workItemManager, times(1)).completeWorkItem(eq(1L), isNull());
        assertNotEquals(initialReleaseId.getVersion(), kieContainer.getReleaseId().getVersion(),
                "KieContainer version should have been incremented");

        // Assert: Verify the new rule is active and fires in a new session
        KieSession updatedSession = kieContainer.newKieSession();
        try {
            int rulesFired = updatedSession.fireAllRules();
            assertEquals(1, rulesFired, "The newly injected 'Trivial Rule' should fire once");
        } finally {
            updatedSession.dispose();
        }
    }

    /**
     * Verifies that submitting syntactically invalid DRL causes
     * {@link DynamicDrlUpdateWorkItemHandler#executeWorkItem} to fail fast by throwing a
     * {@link RuntimeException} containing compilation error details without invoking
     * {@link WorkItemManager#completeWorkItem(long, Map)}.
     */
    @Test
    void testExecuteWorkItem_InvalidDrlThrowsException() {
        // Arrange: Malformed DRL
        String invalidDrl = "package uk.gov.ch.rules; rule \"Broken\" when InvalidSyntax then end";

        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setId(2L);
        Map<String, Object> params = new HashMap<>();
        params.put("DrlContent", invalidDrl);
        workItem.setParameters(params);

        WorkItemManager workItemManager = mock(WorkItemManager.class);

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
            handler.executeWorkItem(workItem, workItemManager)
        );

        assertTrue(exception.getMessage().contains("DRL Compilation Errors"));
        verify(workItemManager, never()).completeWorkItem(anyLong(), any());
    }
}
