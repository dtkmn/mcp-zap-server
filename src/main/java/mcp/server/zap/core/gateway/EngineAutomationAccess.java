package mcp.server.zap.core.gateway;

import java.util.List;

/**
 * Gateway-facing access contract for engine automation plans.
 */
public interface EngineAutomationAccess {

    String runAutomationPlan(String zapPlanPath);

    AutomationPlanProgress loadAutomationPlanProgress(String planId);

    record AutomationPlanProgress(
            String started,
            String finished,
            List<String> info,
            List<String> warnings,
            List<String> errors
    ) {
        public AutomationPlanProgress {
            info = info == null ? List.of() : List.copyOf(info);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean completed() {
            return finished != null && !finished.isBlank();
        }

        public boolean successful() {
            return completed() && errors.isEmpty();
        }
    }
}
