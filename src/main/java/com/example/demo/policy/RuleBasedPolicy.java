package com.example.demo.policy;

import com.example.demo.model.ActionCandidate;
import com.example.demo.model.EncodedState;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class RuleBasedPolicy {

    private final Set<String> visitedActionIds = new HashSet<>();

    public ActionCandidate select(EncodedState state, List<ActionCandidate> candidates) {
        for (ActionCandidate candidate : candidates) {
            double score = 0.0;

            if (!visitedActionIds.contains(candidate.getActionId())) {
                score += 10.0;
            }

            String label = candidate.getLabel() == null ? "" : candidate.getLabel();
            String lower = label.toLowerCase(Locale.ROOT);

            if (lower.contains("next") || lower.contains("다음")) score += 5.0;
            if (lower.contains("cart") || lower.contains("장바구니")) score += 4.0;
            if (lower.contains("checkout") || lower.contains("결제")) score += 6.0;
            if (lower.contains("login") || lower.contains("로그인")) score += 3.0;

            if (lower.contains("delete") || lower.contains("logout") || lower.contains("삭제") || lower.contains("로그아웃")) {
                score -= 20.0;
            }

            candidate.setScore(score);
        }

        ActionCandidate best = candidates.stream()
                .max(Comparator.comparingDouble(ActionCandidate::getScore))
                .orElse(null);

        if (best != null) {
            visitedActionIds.add(best.getActionId());
        }

        return best;
    }
}
