package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * V162: pure, deterministic cost-coverage + explicitly partial contribution
 * waterfall. This does NOT post ledgers, verify Ads/Finances/settlements,
 * allocate refunds, or recognize net profit. No network, DB or tenant scope:
 * callers must supply already-scoped, independently reviewed sources.
 */
public final class FullCostCoverageEngine {
    private FullCostCoverageEngine() {}

    /** Additional costs OUTSIDE the existing V33 Amazon/FBA signed-fee base. */
    public enum Category {
        ADS_SPEND, INBOUND_FREIGHT, FBA_STORAGE, FBM_SHIPPING,
        RETURN_HANDLING, IMPORT_DUTY, PAYMENT_AND_FX, OTHER_OPERATING
    }
    public enum State {
        UNKNOWN, UNVERIFIED_COST, REVIEWED_COST, REVIEWED_ZERO,
        REVIEWED_NOT_APPLICABLE
    }

    /**
     * REVIEWED is a caller-provided local reference status, NOT an independently
     * verified third-party transaction. All state transitions still require
     * recorded human approval, freshness and origin review at the service layer.
     */
    public record Evidence(Category category, State state, String currency,
        BigDecimal amount, String sourceType, String sourceId,
        boolean independentlyReviewedOutsideBase) {}

    public record Coverage(Category category, String state, String knownExpense,
        boolean includedInPreview) {}

    public record Preview(String state, String currency,
        String baselineContribution, String additionalReviewedExpense,
        String knownCostAdjustedContribution,
        int coveredCategories, int totalCategories, List<Category> missingCategories,
        List<Coverage> categories, boolean fullCostSourceComplete,
        boolean bankReconciled, boolean ledgerPosted,
        boolean realizedNetProfitVerified, String realizedNetProfit) {}

    private static BigDecimal money(BigDecimal amount) {
        if (amount == null || amount.scale() > 6 || amount.precision() > 20
            || amount.abs().compareTo(new BigDecimal("99999999999999.999999")) > 0)
            throw new IllegalArgumentException("Invalid money precision");
        return amount;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || !value.matches("-?[0-9]{1,14}(\\.[0-9]{1,6})?"))
            throw new IllegalArgumentException("Invalid source contribution");
        return money(new BigDecimal(value));
    }

    /**
     * Duplicate source keys are forbidden EVEN across cost categories.
     * Finances/Settlement are not acceptable as ADDITIONAL fee sources
     * since V33 may already contain their signed fees or sales.
     */
    public static Preview calculate(OrderContributionEngine.Preview base,
        List<Evidence> inputs) {
        Objects.requireNonNull(base);
        Objects.requireNonNull(inputs);
        if (base.currency() == null || !base.currency().matches("[A-Z]{3}")
            || inputs.size() > Category.values().length)
            throw new IllegalArgumentException("Invalid cost preview scope");

        Map<Category, Evidence> byCategory = new EnumMap<>(Category.class);
        Set<String> uniqueSources = new HashSet<>();
        for (Evidence item: inputs) {
            if (item == null || item.category() == null || item.state() == null
                || byCategory.putIfAbsent(item.category(), item) != null)
                throw new IllegalArgumentException("Missing or repeated cost category");
            if (item.currency() != null && !base.currency().equals(item.currency()))
                throw new IllegalArgumentException("Mixed original currencies");

            boolean sourced = item.state() != State.UNKNOWN;
            if (!sourced) {
                if (item.amount() != null || item.sourceType() != null
                    || item.sourceId() != null || item.independentlyReviewedOutsideBase())
                    throw new IllegalArgumentException("Unknown cost cannot have a claimed amount");
                continue;
            }

            if (item.currency() == null || item.sourceType() == null
                || item.sourceId() == null || item.sourceId().isBlank()
                || item.sourceId().length() > 128
                || !item.sourceId().matches("[A-Za-z0-9_.:/-]+")
                || item.sourceType().length() > 32
                || !item.sourceType().matches("[A-Z_]+")
                || Set.of("FINANCES", "SETTLEMENT", "ORDERS").contains(item.sourceType())
                || !uniqueSources.add(item.sourceType() + "\u0000" + item.sourceId()))
                throw new IllegalArgumentException("Invalid, overlapping or duplicate cost provenance");

            if (item.state() == State.REVIEWED_NOT_APPLICABLE) {
                if (item.amount() != null || !item.independentlyReviewedOutsideBase())
                    throw new IllegalArgumentException("Non-applicability needs reviewed evidence");
            } else {
                money(item.amount());
                if (item.amount().signum() < 0)
                    throw new IllegalArgumentException("Cost must be positive; credits need separate policy");
                if (item.state() == State.REVIEWED_ZERO && item.amount().signum() != 0)
                    throw new IllegalArgumentException("A proven zero cannot conceal a cost");
                if ((item.state() == State.REVIEWED_COST && item.amount().signum() <= 0)
                    || (item.state() == State.UNVERIFIED_COST && item.amount().signum() <= 0))
                    throw new IllegalArgumentException("Zero requires its own reviewed state");
                if (item.state() != State.UNVERIFIED_COST
                    && !item.independentlyReviewedOutsideBase())
                    throw new IllegalArgumentException("Unreviewed expense cannot reduce contribution");
                if (item.state() == State.UNVERIFIED_COST
                    && item.independentlyReviewedOutsideBase())
                    throw new IllegalArgumentException("Unverified cost cannot claim approval");
            }
        }

        BigDecimal original = null;
        if ("REVIEWED_SOURCE_CONTRIBUTION_PREVIEW".equals(base.readiness())) {
            if (base.financialSourceComplete() || base.bankSettlementVerified())
                throw new IllegalArgumentException("V33 preview cannot certify source/settlement");
            BigDecimal sales = decimal(base.signedSales());
            BigDecimal amazonFees = decimal(base.signedAmazonFees());
            BigDecimal fbaFees = decimal(base.signedFbaFees());
            BigDecimal cogs = decimal(base.provisionalUnitCost());
            original = decimal(base.estimatedContribution());
            if (sales.add(amazonFees).add(fbaFees).subtract(cogs).compareTo(original) != 0)
                throw new IllegalArgumentException("Source contribution arithmetic disagrees");
        }

        BigDecimal additional = BigDecimal.ZERO;
        List<Category> missing = new ArrayList<>();
        List<Coverage> coverage = new ArrayList<>();
        for (Category category: Category.values()) {
            Evidence evidence = byCategory.get(category);
            if (evidence == null || evidence.state() == State.UNKNOWN
                || evidence.state() == State.UNVERIFIED_COST)
                missing.add(category);
            boolean included = evidence != null && evidence.state() == State.REVIEWED_COST;
            if (included)
                additional = additional.add(evidence.amount());
            coverage.add(new Coverage(category,
                evidence == null ? State.UNKNOWN.name() : evidence.state().name(),
                included ? evidence.amount().toPlainString() : null, included));
        }
        boolean completeList = missing.isEmpty();
        String status = original == null ? "BASE_CONTRIBUTION_NOT_READY" :
            completeList ? "ALL_CATEGORIES_REFERENCED_PREVIEW_ONLY"
                : "COST_COVERAGE_PARTIAL_PREVIEW";
        // Even a fully referenced list cannot prove real-world completeness,
        // global snapshot consistency, Ads invoices, refund COGS or net profit.
        return new Preview(status, base.currency(),
            original == null ? null : original.toPlainString(),
            original == null ? null : additional.toPlainString(),
            original == null ? null : original.subtract(additional).toPlainString(),
            Category.values().length - missing.size(), Category.values().length,
            List.copyOf(missing), List.copyOf(coverage),
            false, false, false, false, null);
    }
}
