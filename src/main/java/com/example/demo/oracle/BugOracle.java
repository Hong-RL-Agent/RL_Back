package com.example.demo.oracle;

import com.example.demo.model.ActionCandidate;
import com.example.demo.model.ActionExecutionResult;
import com.example.demo.model.DetectedBug;
import com.example.demo.model.EncodedState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BugOracle {

    public List<DetectedBug> detect(
            EncodedState beforeState,
            ActionCandidate action,
            ActionExecutionResult result
    ) {
        List<DetectedBug> bugs = new ArrayList<>();

        for (String error : result.getConsoleErrors()) {
            bugs.add(buildBug(
                    "error",
                    "runtime",
                    "Console Error Detected",
                    error,
                    beforeState.getStateId(),
                    action.getActionId()
            ));
        }

        for (String error : result.getNetworkErrors()) {
            bugs.add(buildBug(
                    "warning",
                    "network",
                    "Network Failure Detected",
                    error,
                    beforeState.getStateId(),
                    action.getActionId()
            ));
        }

        if (!result.isSuccess()) {
            bugs.add(buildBug(
                    "warning",
                    "logic",
                    "Action Execution Failed",
                    "행동 실행에 실패했습니다: " + result.getActionSummary(),
                    beforeState.getStateId(),
                    action.getActionId()
            ));
        }

        if (result.isSuccess() && !result.isDomChanged()
                && safe(result.getPreviousUrl()).equals(safe(result.getNewUrl()))) {
            bugs.add(buildBug(
                    "warning",
                    "ui",
                    "No Visible Reaction After Action",
                    "클릭 이후 URL/DOM 변화가 거의 없습니다: " + result.getActionSummary(),
                    beforeState.getStateId(),
                    action.getActionId()
            ));
        }

        return bugs;
    }

    private DetectedBug buildBug(
            String severity,
            String category,
            String title,
            String detail,
            String stateId,
            String actionId
    ) {
        DetectedBug bug = new DetectedBug();
        bug.setBugId(UUID.randomUUID().toString());
        bug.setSeverity(severity);
        bug.setCategory(category);
        bug.setTitle(title);
        bug.setDetail(detail);
        bug.setStateId(stateId);
        bug.setActionId(actionId);
        return bug;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}