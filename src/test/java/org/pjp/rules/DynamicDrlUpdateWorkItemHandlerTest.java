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

class DynamicDrlUpdateWorkItemHandlerTest {

    private KieServices kieServices;
    private KieContainer kieContainer;
    private ReleaseId initialReleaseId;
    private DynamicDrlUpdateWorkItemHandler handler;

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
