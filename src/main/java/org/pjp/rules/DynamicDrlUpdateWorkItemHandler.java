package org.pjp.rules;

import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.KieContainerImpl;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.process.WorkItem;
import org.kie.api.runtime.process.WorkItemHandler;
import org.kie.api.runtime.process.WorkItemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamicDrlUpdateWorkItemHandler implements WorkItemHandler {

    // Simple timestamp-based versioning to guarantee uniqueness
    private static String incrementVersion(String currentVersion) {
        String baseVersion = currentVersion.split("-")[0];
        return baseVersion + "-" + System.currentTimeMillis();
    }

    private static Logger LOGGER = LoggerFactory.getLogger(DynamicDrlUpdateWorkItemHandler.class);

    private static final String VERSION = "v1.1.3";

    private final KieContainer kieContainer;

    // Inject the active KieContainer from your RuntimeManager / DeploymentUnit.
    public DynamicDrlUpdateWorkItemHandler(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    @Override
    public void executeWorkItem(WorkItem workItem, WorkItemManager manager) {
        LOGGER.info("DynamicDrlUpdateWorkItemHandler {} executing...", VERSION);

        String drlContent = (String) workItem.getParameter("DrlContent");
        String ruleFileName = (String) workItem.getParameter("RuleFileName");	// should be full path including package folders and filename should have .drl extension

        String safeFileName = (ruleFileName != null && !ruleFileName.trim().isEmpty())
                ? (ruleFileName.endsWith(".drl") ? ruleFileName.trim() : ruleFileName.trim() + ".drl")
                : "updatedRule.drl";

        LOGGER.debug("safeFileName = {}", safeFileName);

        if (drlContent == null || drlContent.trim().isEmpty()) {
            LOGGER.warn("DRL content is empty");
        } else {
            KieServices ks = KieServices.Factory.get();
            KieFileSystem kfs = ks.newKieFileSystem();

            // 1. Copy ALL existing assets (.bpmn, .class, existing .drl, etc.) from the active KieModule
            InternalKieModule currentModule = (InternalKieModule) ((KieContainerImpl) kieContainer).getMainKieModule();

            if (currentModule != null) {
                for (String fileName : currentModule.getFileNames()) {
                    LOGGER.debug("reading file {}", fileName);
                    // Skip pom.xml (and the old version of the DRL file we are about to overwrite)
                    if (fileName.equals("pom.xml") || fileName.endsWith("pom.properties")) {
                        continue;
                    }

                    if (safeFileName != null && fileName.endsWith(safeFileName)) {
                        continue;
                    }

                    byte[] bytes = currentModule.getBytes(fileName);
                    if (bytes != null) {
                        // In a compiled KJAR, resource paths don't have the "src/main/resources/" prefix,
                        // whereas KieFileSystem expects non-class resources under "src/main/resources/"
                        String targetPath = fileName.endsWith(".class")
                                ? fileName
                                : "src/main/resources/" + fileName;
                        LOGGER.debug("copying bytes to target {}", targetPath);
                        kfs.write(targetPath, bytes);
                    }
                }
            }

            // 2. Write the new/updated DRL string
            LOGGER.debug("writing new/updated DRL to src/main/resources/{}", safeFileName);
            kfs.write("src/main/resources/" + safeFileName, drlContent);

            // 3. Increment the ReleaseId version
            ReleaseId currentRelease = kieContainer.getReleaseId();
            ReleaseId newRelease = ks.newReleaseId(
                    currentRelease.getGroupId(),
                    currentRelease.getArtifactId(),
                    incrementVersion(currentRelease.getVersion())
            );
            kfs.generateAndWritePomXML(newRelease);

            LOGGER.info("KIE newRelease = {}", newRelease);

            // 4. Build using the existing KieContainer's ClassLoader so project Java types resolve
            LOGGER.debug("building new KIE version");
            KieBuilder kieBuilder = ks.newKieBuilder(kfs, kieContainer.getClassLoader()).buildAll();
            if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
                throw new RuntimeException("DRL Compilation Errors: " + kieBuilder.getResults().getMessages());
            }

            // 5. Hot-swap the KieContainer
            LOGGER.debug("updating KIE to new version");
            kieContainer.updateToVersion(newRelease);
        }

        manager.completeWorkItem(workItem.getId(), null);
    }

    @Override
    public void abortWorkItem(WorkItem workItem, WorkItemManager manager) {
        manager.abortWorkItem(workItem.getId());
    }
}
