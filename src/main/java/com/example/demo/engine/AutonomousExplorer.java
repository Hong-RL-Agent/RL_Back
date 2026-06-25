package com.example.demo.engine;

import com.example.demo.agent.PlaywrightExecutor;
import com.example.demo.graph.StateGraph;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.example.demo.model.ActionCandidate;
import com.example.demo.model.ActionExecutionResult;
import com.example.demo.model.DetectedBug;
import com.example.demo.model.EncodedState;
import com.example.demo.observer.ActionExtractor;
import com.example.demo.observer.StateEncoder;
import com.example.demo.oracle.BugOracle;
import com.example.demo.policy.RuleBasedPolicy;
import com.microsoft.playwright.Page;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class AutonomousExplorer {

    private static final DateTimeFormatter RESULT_FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PlaywrightExecutor executor;
    private final ActionExtractor actionExtractor;
    private final StateEncoder stateEncoder;
    private final RuleBasedPolicy policy;
    private final StateGraph stateGraph;
    private final BugOracle bugOracle;
    private final Path outputDirectory;

    public AutonomousExplorer(boolean headless) {
        this(headless, Path.of("exploration-results"));
    }

    public AutonomousExplorer(boolean headless, Path outputDirectory) {
        this.executor = new PlaywrightExecutor(headless);
        this.actionExtractor = new ActionExtractor();
        this.stateEncoder = new StateEncoder();
        this.policy = new RuleBasedPolicy();
        this.stateGraph = new StateGraph();
        this.bugOracle = new BugOracle();
        this.outputDirectory = outputDirectory;
    }

    public void explore(String targetUrl, int maxSteps) {
        List<DetectedBug> detectedBugs = new ArrayList<>();
        List<TickCapture> tickCaptures = new ArrayList<>();
        List<ActionCandidateSnapshot> actionCandidateSnapshots = new ArrayList<>();
        String runId = LocalDateTime.now().format(RESULT_FILE_TIME_FORMAT);

        try {
            executor.open(targetUrl);
            Page page = executor.getPage();

            EncodedState currentState = stateEncoder.encode(
                    page,
                    executor.getTotalConsoleErrorCount(),
                    executor.getTotalNetworkErrorCount()
            );
            stateGraph.addState(currentState);

            System.out.println("[START] Initial state");
            printState(currentState);

            for (int step = 1; step <= maxSteps; step++) {
                System.out.println("\n================ STEP " + step + " ================");

                List<ActionCandidate> candidates = actionExtractor.extract(page);

                if (candidates.isEmpty()) {
                    System.out.println("[STOP] No executable actions found.");
                    tickCaptures.add(new TickCapture(
                            runId,
                            LocalDateTime.now().toString(),
                            step,
                            "no_candidates",
                            currentState,
                            null,
                            null,
                            false,
                            false,
                            false,
                            currentState,
                            0,
                            0,
                            0,
                            0
                    ));
                    break;
                }

                System.out.println("[INFO] Extracted actions: " + candidates.size());

                ActionCandidate selected = policy.select(currentState, candidates);
                captureActionCandidates(runId, step, currentState, candidates, selected, actionCandidateSnapshots);
                if (selected == null) {
                    System.out.println("[STOP] No action selected.");
                    tickCaptures.add(new TickCapture(
                            runId,
                            LocalDateTime.now().toString(),
                            step,
                            "no_selection",
                            currentState,
                            null,
                            null,
                            false,
                            false,
                            false,
                            currentState,
                            candidates.size(),
                            0,
                            0,
                            0
                    ));
                    break;
                }

                System.out.println("[SELECTED ACTION] " + selected);

                ActionExecutionResult result = executor.execute(selected, currentState.getStateId());
                EncodedState nextState = stateEncoder.encode(
                        page,
                        executor.getTotalConsoleErrorCount(),
                        executor.getTotalNetworkErrorCount()
                );
                result.setAfterStateId(nextState.getStateId());

                boolean isNewState = stateGraph.isNewState(nextState);
                stateGraph.addState(nextState);
                stateGraph.addEdge(currentState, selected, nextState);

                System.out.println("[RESULT] success=" + result.isSuccess()
                        + ", domChanged=" + result.isDomChanged()
                        + ", newUrl=" + result.getNewUrl()
                        + ", isNewState=" + isNewState);
                printTransition(currentState, selected, nextState);

                List<DetectedBug> bugs = bugOracle.detect(currentState, selected, result);
                detectedBugs.addAll(bugs);
                for (DetectedBug bug : bugs) {
                    System.out.println("[BUG] " + bug);
                }

                tickCaptures.add(new TickCapture(
                        runId,
                        LocalDateTime.now().toString(),
                        step,
                        "executed",
                        currentState,
                        selected,
                        result,
                        result.isSuccess(),
                        result.isDomChanged(),
                        isNewState,
                        nextState,
                        candidates.size(),
                        result.getConsoleErrors().size(),
                        result.getNetworkErrors().size(),
                        bugs.size()
                ));

                currentState = nextState;
                printState(currentState);
            }

            stateGraph.printSummary();
            Path resultPath = writeResult(targetUrl, maxSteps, detectedBugs);
            System.out.println("[RESULT FILE] " + resultPath.toAbsolutePath());
            Path tickCsvPath = writeTickActionsCsv(runId, tickCaptures);
            System.out.println("[TICK CSV FILE] " + tickCsvPath.toAbsolutePath());
            Path actionCsvPath = writeActionCandidatesCsv(runId, actionCandidateSnapshots);
            System.out.println("[ACTION CSV FILE] " + actionCsvPath.toAbsolutePath());
            Path csvPath = appendTransitionsCsv(targetUrl);
            System.out.println("[CSV FILE] " + csvPath.toAbsolutePath());

        } finally {
            executor.close();
        }
    }

    private void captureActionCandidates(
            String runId,
            int step,
            EncodedState state,
            List<ActionCandidate> candidates,
            ActionCandidate selected,
            List<ActionCandidateSnapshot> snapshots
    ) {
        for (int i = 0; i < candidates.size(); i++) {
            ActionCandidate candidate = candidates.get(i);
            boolean isSelected = selected != null && selected.getActionId().equals(candidate.getActionId());
            snapshots.add(new ActionCandidateSnapshot(
                    runId,
                    LocalDateTime.now().toString(),
                    step,
                    state,
                    i + 1,
                    candidates.size(),
                    candidate,
                    isSelected
            ));
        }
    }

    private void printState(EncodedState state) {
        System.out.println("[STATE] stateId=" + state.getStateId()
                + ", pageType=" + state.getPageType()
                + ", url=" + state.getUrl()
                + ", title=" + state.getTitle()
                + ", clickables=" + state.getClickableCount()
                + ", forms=" + state.getFormCount()
                + ", modal=" + state.isHasModal()
                + ", consoleErrors=" + state.getConsoleErrorCount()
                + ", networkErrors=" + state.getNetworkErrorCount());
    }

    private void printTransition(EncodedState from, ActionCandidate action, EncodedState to) {
        System.out.println("[TRANSITION] fromStateId=" + from.getStateId()
                + " -- actionId=" + action.getActionId()
                + ", type=" + action.getType()
                + ", label=" + printable(action.getLabel())
                + ", selector=" + printable(action.getSelector())
                + ", index=" + action.getIndex()
                + " --> toStateId=" + to.getStateId()
                + ", toPageType=" + to.getPageType()
                + ", toUrl=" + to.getUrl());
    }

    private String printable(String value) {
        return value == null || value.isBlank() ? "(empty)" : value;
    }

    private Path writeResult(String targetUrl, int maxSteps, List<DetectedBug> detectedBugs) {
        try {
            Files.createDirectories(outputDirectory);
            String fileName = "exploration-result-"
                    + LocalDateTime.now().format(RESULT_FILE_TIME_FORMAT)
                    + ".json";
            Path resultPath = outputDirectory.resolve(fileName);

            ExplorationResult result = new ExplorationResult(
                    LocalDateTime.now().toString(),
                    targetUrl,
                    maxSteps,
                    stateGraph.getStates(),
                    stateGraph.getTransitions(),
                    detectedBugs
            );

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(resultPath, gson.toJson(result), StandardCharsets.UTF_8);
            return resultPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write exploration result file", e);
        }
    }

    private Path appendTransitionsCsv(String targetUrl) {
        try {
            Files.createDirectories(outputDirectory);
            Path csvPath = outputDirectory.resolve("exploration-transitions.csv");
            boolean needsHeader = Files.notExists(csvPath) || Files.size(csvPath) == 0;

            List<String> lines = new ArrayList<>();
            if (needsHeader) {
                lines.add("\uFEFF" + String.join(",",
                        "generatedAt",
                        "targetUrl",
                        "fromStateId",
                        "fromPageType",
                        "fromUrl",
                        "actionId",
                        "actionType",
                        "actionLabel",
                        "actionSelector",
                        "actionIndex",
                        "toStateId",
                        "toPageType",
                        "toUrl"
                ));
            }

            String generatedAt = LocalDateTime.now().toString();
            for (StateGraph.StateTransition transition : stateGraph.getTransitions()) {
                lines.add(String.join(",",
                        csv(generatedAt),
                        csv(targetUrl),
                        csv(transition.getFrom().getStateId()),
                        csv(transition.getFrom().getPageType()),
                        csv(transition.getFrom().getUrl()),
                        csv(transition.getAction().getActionId()),
                        csv(transition.getAction().getType()),
                        csv(transition.getAction().getLabel()),
                        csv(transition.getAction().getSelector()),
                        String.valueOf(transition.getAction().getIndex()),
                        csv(transition.getTo().getStateId()),
                        csv(transition.getTo().getPageType()),
                        csv(transition.getTo().getUrl())
                ));
            }

            if (!lines.isEmpty()) {
                Files.write(csvPath, lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return csvPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to append exploration transitions CSV", e);
        }
    }

    private Path writeTickActionsCsv(String runId, List<TickCapture> rows) {
        try {
            Files.createDirectories(outputDirectory);
            Path csvPath = outputDirectory.resolve("tick-actions-" + runId + ".csv");

            List<String> lines = new ArrayList<>();
            lines.add("\uFEFF" + String.join(",",
                    "runId",
                    "capturedAt",
                    "tick",
                    "tickStatus",
                    "fromStateId",
                    "fromPageType",
                    "fromUrl",
                    "candidateCount",
                    "selectedActionId",
                    "selectedActionType",
                    "selectedActionLabel",
                    "selectedActionSelector",
                    "selectedActionIndex",
                    "selectedActionScore",
                    "executionSuccess",
                    "domChanged",
                    "newState",
                    "toStateId",
                    "toPageType",
                    "toUrl",
                    "consoleErrorsAdded",
                    "networkErrorsAdded",
                    "bugsDetected",
                    "previousUrl",
                    "newUrl",
                    "actionSummary"
            ));

            for (TickCapture row : rows) {
                ActionCandidate action = row.selectedAction();
                ActionExecutionResult result = row.result();
                lines.add(String.join(",",
                        csv(row.runId()),
                        csv(row.capturedAt()),
                        String.valueOf(row.tick()),
                        csv(row.tickStatus()),
                        csv(row.from().getStateId()),
                        csv(row.from().getPageType()),
                        csv(row.from().getUrl()),
                        String.valueOf(row.candidateCount()),
                        csv(action == null ? "" : action.getActionId()),
                        csv(action == null ? "" : action.getType()),
                        csv(action == null ? "" : action.getLabel()),
                        csv(action == null ? "" : action.getSelector()),
                        action == null ? "" : String.valueOf(action.getIndex()),
                        action == null ? "" : String.valueOf(action.getScore()),
                        String.valueOf(row.executionSuccess()),
                        String.valueOf(row.domChanged()),
                        String.valueOf(row.newState()),
                        csv(row.to().getStateId()),
                        csv(row.to().getPageType()),
                        csv(row.to().getUrl()),
                        String.valueOf(row.consoleErrorsAdded()),
                        String.valueOf(row.networkErrorsAdded()),
                        String.valueOf(row.bugsDetected()),
                        csv(result == null ? "" : result.getPreviousUrl()),
                        csv(result == null ? "" : result.getNewUrl()),
                        csv(result == null ? "" : result.getActionSummary())
                ));
            }

            Files.write(csvPath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return csvPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write tick actions CSV", e);
        }
    }

    private Path writeActionCandidatesCsv(String runId, List<ActionCandidateSnapshot> rows) {
        try {
            Files.createDirectories(outputDirectory);
            Path csvPath = outputDirectory.resolve("action-candidates-" + runId + ".csv");

            List<String> lines = new ArrayList<>();
            lines.add("\uFEFF" + String.join(",",
                    "runId",
                    "capturedAt",
                    "tick",
                    "fromStateId",
                    "fromPageType",
                    "fromUrl",
                    "candidateRank",
                    "candidateCount",
                    "actionId",
                    "actionType",
                    "actionLabel",
                    "actionSelector",
                    "actionIndex",
                    "actionScore",
                    "selected"
            ));

            for (ActionCandidateSnapshot row : rows) {
                ActionCandidate action = row.action();
                lines.add(String.join(",",
                        csv(row.runId()),
                        csv(row.capturedAt()),
                        String.valueOf(row.tick()),
                        csv(row.from().getStateId()),
                        csv(row.from().getPageType()),
                        csv(row.from().getUrl()),
                        String.valueOf(row.candidateRank()),
                        String.valueOf(row.candidateCount()),
                        csv(action.getActionId()),
                        csv(action.getType()),
                        csv(action.getLabel()),
                        csv(action.getSelector()),
                        String.valueOf(action.getIndex()),
                        String.valueOf(action.getScore()),
                        String.valueOf(row.selected())
                ));
            }

            Files.write(csvPath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return csvPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write action candidates CSV", e);
        }
    }

    private String csv(String value) {
        String safeValue = value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private record ExplorationResult(
            String generatedAt,
            String targetUrl,
            int maxSteps,
            Object states,
            Object transitions,
            Object bugs
    ) {
    }

    private record TickCapture(
            String runId,
            String capturedAt,
            int tick,
            String tickStatus,
            EncodedState from,
            ActionCandidate selectedAction,
            ActionExecutionResult result,
            boolean executionSuccess,
            boolean domChanged,
            boolean newState,
            EncodedState to,
            int candidateCount,
            int consoleErrorsAdded,
            int networkErrorsAdded,
            int bugsDetected
    ) {
    }

    private record ActionCandidateSnapshot(
            String runId,
            String capturedAt,
            int tick,
            EncodedState from,
            int candidateRank,
            int candidateCount,
            ActionCandidate action,
            boolean selected
    ) {
    }
}
