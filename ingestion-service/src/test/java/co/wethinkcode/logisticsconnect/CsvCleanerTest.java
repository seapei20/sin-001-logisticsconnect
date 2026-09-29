package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cleaning rules against the real {@code hubs-global.csv} plus inline fixtures for
 * cases the shipped file does not contain.
 *
 * <p>Each test names the data issue it is about rather than just the method under test, so a
 * failure points at the rule that broke instead of at a line number.
 */
class CsvCleanerTest {

    private static final Path REAL_CSV = Path.of("src/main/resources/hubs-global.csv");

    private static List<Hub> clean(String csv) throws IOException {
        return CsvCleaner.clean(new StringReader(csv));
    }

    private static Hub cleanOne(String row) throws IOException {
        List<Hub> hubs = clean("hub_id,province,sorting_center,active\n" + row + "\n");
        assertEquals(1, hubs.size(), "expected exactly one hub from: " + row);
        return hubs.get(0);
    }

    private static Map<String, Hub> byId(List<Hub> hubs) {
        return hubs.stream().collect(Collectors.toMap(Hub::hubId, Function.identity()));
    }

    @Nested
    @DisplayName("the shipped hubs-global.csv")
    class RealFile {

        @Test
        @DisplayName("is parsed, deduplicated and normalized end to end")
        void cleansTheRealFile() throws IOException {
            List<Hub> hubs = CsvCleaner.clean(REAL_CSV);

            assertFalse(hubs.isEmpty(), "expected the real CSV to yield hubs");
            assertEquals(18, hubs.size(), "18 distinct hub ids in the shipped file");

            // The two worked-example rows from ingestion-service/README.md.
            assertEquals(
                    new Hub("H-502", "Gauteng", "Pretoria North", false),
                    byId(hubs).get("H-502"),
                    "README worked example: H-502 padding + casing + 0 -> false");
            assertEquals(
                    new Hub("H-505", "Western Cape", "Cape Town Port", true),
                    byId(hubs).get("H-505"),
                    "README worked example: H-505 double space + TRUE -> true");
        }

        @Test
        @DisplayName("yields only normalized ids, no lowercase or padded survivors")
        void everyIdIsNormalized() throws IOException {
            List<Hub> hubs = CsvCleaner.clean(REAL_CSV);

            for (Hub hub : hubs) {
                assertEquals(hub.hubId().toUpperCase(), hub.hubId(),
                        "hub id should be upper-cased: " + hub.hubId());
                assertEquals(hub.hubId().trim(), hub.hubId(),
                        "hub id should have no padding: " + hub.hubId());
            }
        }

        @Test
        @DisplayName("collapses internal double spaces in sorting centers")
        void collapsesDoubleSpaces() throws IOException {
            Hub h505 = byId(CsvCleaner.clean(REAL_CSV)).get("H-505");

            assertEquals("Cape Town Port", h505.sortingCenter(),
                    "\"Cape Town  Port\" should collapse to one space");
        }
    }

    @Nested
    @DisplayName("casing")
    class Casing {

        @Test
        @DisplayName("upper-cases hub ids so H-500 and h-500 are the same hub")
        void upperCasesHubIds() throws IOException {
            assertEquals("H-501", cleanOne("h-501,Gauteng,Pretoria,Y").hubId());
        }

        @Test
        @DisplayName("title-cases sorting centers")
        void titleCasesSortingCenters() throws IOException {
            assertEquals("Johannesburg Central",
                    cleanOne("H-510,Gauteng,johannesburg central,Y").sortingCenter());
        }

        @Test
        @DisplayName("normalizes province spelling variants to one form")
        void normalizesProvinceVariants() throws IOException {
            // The shipped file contains "Kwa-Zulu Natal" and "KwaZulu Natal" for the same province.
            Map<String, Hub> hubs = byId(CsvCleaner.clean(REAL_CSV));

            assertEquals("KwaZulu-Natal", hubs.get("H-506").province(), "\"Kwa-Zulu Natal\"");
            assertEquals("KwaZulu-Natal", hubs.get("H-516").province(), "\"KwaZulu Natal\"");
            assertEquals("Eastern Cape", hubs.get("H-509").province(), "\"eastern cape\"");
        }
    }

    @Nested
    @DisplayName("padding")
    class Padding {

        @Test
        @DisplayName("trims leading and trailing whitespace")
        void trimsPadding() throws IOException {
            Hub hub = cleanOne("H-500, Gauteng ,Johannesburg Central,Y");

            assertEquals("H-500", hub.hubId());
            assertEquals("Gauteng", hub.province());
        }

        @Test
        @DisplayName("squeezes internal runs of whitespace to a single space")
        void collapsesInternalWhitespace() throws IOException {
            assertEquals("Cape Town Port",
                    cleanOne("H-505,Western Cape,Cape Town  Port,Y").sortingCenter());
        }
    }

    @Nested
    @DisplayName("boolean flags")
    class Booleans {

        @Test
        @DisplayName("accepts every spelling of true")
        void acceptsTrueSpellings() throws IOException {
            for (String value : List.of("Y", "y", "yes", "YES", "1", "true", "TRUE", " true ")) {
                assertTrue(cleanOne("H-500,Gauteng,Pretoria," + value).active(),
                        "\"" + value + "\" should normalize to true");
            }
        }

        @Test
        @DisplayName("accepts every spelling of false")
        void acceptsFalseSpellings() throws IOException {
            for (String value : List.of("N", "n", "no", "NO", "0", "false", "FALSE", " false ")) {
                assertFalse(cleanOne("H-500,Gauteng,Pretoria," + value).active(),
                        "\"" + value + "\" should normalize to false");
            }
        }

        @Test
        @DisplayName("defaults an unreadable flag to active rather than dropping the hub")
        void defaultsUnreadableFlagToActive() throws IOException {
            // \"N/A\" and \"unknown\" appear in the shipped file. Deactivating on a parsing gap
            // would stop parcels moving through a hub that is probably fine.
            assertTrue(cleanOne("H-517,Gauteng,Pretoria,N/A").active());
            assertTrue(cleanOne("H-511,Gauteng,Pretoria,unknown").active());
        }
    }

    @Nested
    @DisplayName("placeholders")
    class Placeholders {

        @Test
        @DisplayName("turns a missing province into Unknown")
        void missingProvinceBecomesUnknown() throws IOException {
            assertEquals("Unknown", cleanOne("H-508,,Pretoria North,Y").province());
        }

        @Test
        @DisplayName("treats N/A and other placeholders as missing")
        void treatsPlaceholdersAsMissing() throws IOException {
            for (String value : List.of("N/A", "n/a", "TBD", "-", "unknown", "   ")) {
                assertEquals("Unknown", cleanOne("H-500," + value + ",Pretoria,Y").province(),
                        "\"" + value + "\" should be treated as a missing province");
            }
        }

        @Test
        @DisplayName("skips rows whose id is unusable")
        void skipsRowsWithoutAnId() throws IOException {
            List<Hub> hubs = clean("hub_id,province,sorting_center,active\n"
                    + ",Gauteng,Nowhere,Y\n"
                    + "H-500,Gauteng,Pretoria,Y\n");

            assertEquals(1, hubs.size(), "a row with no hub id cannot be served to anyone");
        }
    }

    @Nested
    @DisplayName("deduplication")
    class Deduplication {

        @Test
        @DisplayName("collapses ids that differ only in casing")
        void collapsesCaseVariants() throws IOException {
            List<Hub> hubs = clean("hub_id,province,sorting_center,active\n"
                    + "H-500,Gauteng,Johannesburg Central,Y\n"
                    + "h-500,Gauteng,Johannesburg Central,Y\n");

            assertEquals(1, hubs.size(), "H-500 and h-500 are the same hub");
        }

        @Test
        @DisplayName("keeps a hub active if any duplicate record says it is")
        void activeWinsOverInactive() {
            // The conservative choice: a facility reported active by any record stays active,
            // because wrongly deactivating one stops parcels through a working hub.
            Hub merged = CsvCleaner.mergeHubs(
                    new Hub("H-500", "Gauteng", "Johannesburg Central", false),
                    new Hub("H-500", "Gauteng", "Johannesburg Central", true));

            assertTrue(merged.active(), "one active record should win over one inactive");
        }

        @Test
        @DisplayName("lets a real province overwrite a placeholder one")
        void realProvinceOverwritesPlaceholder() {
            Hub merged = CsvCleaner.mergeHubs(
                    new Hub("H-508", "Unknown", "Pretoria North", true),
                    new Hub("H-508", "Gauteng", "Pretoria North", true));

            assertEquals("Gauteng", merged.province(), "a known location should beat \"Unknown\"");
        }

        @Test
        @DisplayName("preserves input order so output is stable")
        void preservesInsertionOrder() throws IOException {
            List<Hub> hubs = clean("hub_id,province,sorting_center,active\n"
                    + "H-503,KwaZulu-Natal,Durban,Y\n"
                    + "H-500,Gauteng,Johannesburg,Y\n"
                    + "H-501,Western Cape,Cape Town,Y\n");

            assertEquals(List.of("H-503", "H-500", "H-501"),
                    hubs.stream().map(Hub::hubId).toList(),
                    "LinkedHashMap should keep first-seen order, not sort");
        }
    }

    @Nested
    @DisplayName("value object")
    class ValueSemantics {

        @Test
        @DisplayName("Hub compares by value, so it works as a map key")
        void hubIsAValueObject() {
            Hub a = new Hub("H-500", "Gauteng", "Johannesburg Central", true);
            Hub b = new Hub("H-500", "Gauteng", "Johannesburg Central", true);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotNull(a.toString(), "toString should be readable in logs");
        }
    }
}
