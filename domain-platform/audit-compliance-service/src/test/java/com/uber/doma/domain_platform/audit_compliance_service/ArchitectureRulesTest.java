package com.uber.doma.domain_platform.audit_compliance_service;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Compile-time architectural enforcement for the audit-compliance-service.
 *
 * <p>This test enforces strict bounded-context isolation as required by the
 * Uber DOMA (Domain-Oriented Microservice Architecture) governance policy.
 * The audit-compliance-service lives inside the {@code domain-platform}
 * bounded context (alongside telemetry-gps-ingestion, event-streaming-hub,
 * experimentation-flipr, service-registry-discovery and
 * distributed-rate-limiter services) and provides the Immutable Regulatory
 * Audit Log and GDPR primitives for the entire fleet.</p>
 *
 * <p>To preserve the integrity of the platform, this service must never
 * import classes that live in a different leaf bounded context. The five
 * forbidden leaf contexts are:</p>
 * <ul>
 *     <li>{@code com.uber.doma.domain_mobility} — deep-eta, dispatch,
 *         surge, pool-batching, supply-locator, demand-pin,
 *         routing-engine, h3-spatial-index, mobility-domain-gateway.</li>
 *     <li>{@code com.uber.doma.domain_trip} — reservation, state-machine,
 *         cancellation, in-trip-chat, lost-and-found, safety-telematics,
 *         toll-highway-surcharge, trip-domain-gateway.</li>
 *     <li>{@code com.uber.doma.domain_billing} — fare-quotation,
 *         fare-split, payment-orchestrator, invoicing-ledger,
 *         promo-rider-discount, driver-instant-payout, tax-compliance,
 *         billing-domain-gateway.</li>
 *     <li>{@code com.uber.doma.domain_driver} — onboarding, compliance,
 *         earnings-analytics, quest-incentives, vehicle-registry,
 *         fleet-partner, driver-domain-gateway.</li>
 *     <li>{@code com.uber.doma.domain_rider} — auth-security,
 *         fraud-gps-spoofing, rider-profile, mutual-rating,
 *         uber-one-membership, omnichannel-notifications,
 *         rider-domain-gateway.</li>
 * </ul>
 *
 * <p>Communication across these boundaries must go exclusively through
 * the {@code common-*} shared infrastructure (common-domain-core,
 * common-grpc, common-kafka, common-redis), the {@code edge-gateway}, or
 * asynchronous messaging contracts defined in {@code proto-contracts}.
 * A direct compile-time import would constitute a hidden coupling and a
 * violation of the bounded-context principle, so we fail the build
 * immediately if it ever appears.</p>
 *
 * <p>The rule scope is restricted to production code (the {@code main}
 * classpath). Test classes are deliberately excluded because the test
 * suite legitimately references configuration fixtures, mock services
 * and integration probes that may live outside the bounded context.
 * Limiting the check to production classes still catches every real
 * coupling while keeping the rule maintainable.</p>
 */
class ArchitectureRulesTest {

    /**
     * The production classpath of the audit-compliance-service. Tests are
     * excluded because the rule targets architectural drift in shipping
     * code only.
     */
    private static final String AUDIT_COMPLIANCE_SERVICE_PACKAGE =
            "com.uber.doma.domain_platform.audit_compliance_service";

    /**
     * Java classes loaded once per test run. Importing the classpath for
     * every rule would be wasteful when the test suite already imports
     * the same set of bytes for every assertion.
     */
    private static JavaClasses importedClasses;

    @BeforeAll
    static void importProductionClasspath() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(AUDIT_COMPLIANCE_SERVICE_PACKAGE);
    }

    @Test
    @DisplayName("No class may depend on domain_mobility (a forbidden leaf bounded context)")
    void classesShouldNotDependOnDomainMobility() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(AUDIT_COMPLIANCE_SERVICE_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.uber.doma.domain_mobility..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("No class may depend on domain_trip (a forbidden leaf bounded context)")
    void classesShouldNotDependOnDomainTrip() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(AUDIT_COMPLIANCE_SERVICE_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.uber.doma.domain_trip..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("No class may depend on domain_billing (a forbidden leaf bounded context)")
    void classesShouldNotDependOnDomainBilling() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(AUDIT_COMPLIANCE_SERVICE_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.uber.doma.domain_billing..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("No class may depend on domain_driver (a forbidden leaf bounded context)")
    void classesShouldNotDependOnDomainDriver() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(AUDIT_COMPLIANCE_SERVICE_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.uber.doma.domain_driver..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("No class may depend on domain_rider (a forbidden leaf bounded context)")
    void classesShouldNotDependOnDomainRider() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(AUDIT_COMPLIANCE_SERVICE_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.uber.doma.domain_rider..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("No class may depend on any forbidden leaf bounded context (aggregate rule)")
    void classesShouldNotDependOnAnyForbiddenLeafDomain() {
        // The aggregate rule collapses the five individual checks into a
        // single, fast-failing assertion. This is the canonical place to
        // look when triaging a CI failure: ArchUnit will report every
        // offending import in one pass, which is far easier to fix than
        // five separate, partial failures.
        ArchRule aggregateRule = noClasses()
                .that()
                .resideInAPackage(AUDIT_COMPLIANCE_SERVICE_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.uber.doma.domain_mobility..",
                        "com.uber.doma.domain_trip..",
                        "com.uber.doma.domain_billing..",
                        "com.uber.doma.domain_driver..",
                        "com.uber.doma.domain_rider..");

        aggregateRule.check(importedClasses);
    }
}
