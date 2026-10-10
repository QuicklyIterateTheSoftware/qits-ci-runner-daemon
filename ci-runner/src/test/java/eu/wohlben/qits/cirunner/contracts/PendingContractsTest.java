package eu.wohlben.qits.cirunner.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Reports every row of {@link PendingContracts} as a skipped test whose reason names the provider
 * state it waits for (qits-1149), so the gap shows in every test run, not only in a document.
 */
class PendingContractsTest {

  @TestFactory
  Stream<DynamicTest> everyUnpactedCallWaitsOnANamedProviderState() {
    assertFalse(PendingContracts.ROWS.isEmpty());
    return PendingContracts.ROWS.stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.provider() + " " + row.method() + " " + row.path() + " " + row.status(),
                    () -> Assumptions.abort(row.reason())));
  }
}
