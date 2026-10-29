package com.metaform.dxonboarding.domain.model.onboarding;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** The TSP's use cases: a request agrees to some of them ({@code ucas}), each by its id. */
public enum UseCase {
    EXPORT_CONTROL("export-control", "Export Control"),
    PRODUCT_CONFORMITY("product-conformity", "Product Conformity"),
    CRITICAL_SUPPLY_CHAIN("critical-supply-chain", "Critical Supply Chain"),
    ENGINEERING("engineering", "Engineering"),
    DEVELOPMENT_AND_INDUSTRIALISATION("development-and-industrialisation", "Development and Industrialisation"),
    DEMAND_AND_CAPACITY_MANAGEMENT("demand-and-capacity-management", "Demand & Capacity Management"),
    NON_QUALITY_MANAGEMENT("non-quality-management", "Non-quality management"),
    SUBSTANCE_TRACKING("substance-tracking", "Substance Tracking");

    private final String id;
    private final String displayName;

    UseCase(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The id on the wire, e.g. {@code product-conformity}. */
    @JsonValue
    public String id() {
        return id;
    }

    /** How the TSP names the use case — the applicant's own {@code useCaseName} is ignored. */
    public String displayName() {
        return displayName;
    }

    public static Optional<UseCase> fromId(String id) {
        return Arrays.stream(values()).filter(useCase -> useCase.id.equals(id)).findFirst();
    }
}
