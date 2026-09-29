package co.wethinkcode.logisticsconnect;

import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the messy legacy {@code hubs-global.csv} export into consistent {@link Hub} records.
 *
 * <p>The file is deliberately inconsistent in the ways real legacy exports always are: padding,
 * mixed casing, and several spellings of the same boolean. Everything is normalized to one
 * convention before anything downstream sees it, because the other services are the ones that
 * have to trust this data.
 *
 * <p>Deduplication is keyed on the normalized {@code hubId} and resolved with two rules, both
 * chosen so a hub is never made to look <em>worse</em> than its data supports:
 * <ul>
 *   <li>an {@code "Unknown"} province is overwritten when a duplicate carries a real one, so
 *       {@code H-508} (blank province) keeps the location that {@code H-502} supplies;</li>
 *   <li>{@code active} is combined with logical OR, so a facility reported active by any of
 *       its records stays active — the conservative choice when a duplicate conflicts, since
 *       wrongly deactivating a hub stops parcels moving through a working facility.</li>
 * </ul>
 * Insertion order is preserved via a {@link LinkedHashMap}, so output order is stable and
 * diffs stay readable.
 */
public class CsvCleaner {

    /**
     * Regional spellings that denote the same province, mapped to the single form used
     * downstream. Matched against an already-trimmed, lower-cased province.
     */
    private static final Map<String, String> PROVINCE_ALIASES = Map.of(
            "kwazulu natal", "KwaZulu-Natal",
            "kwa-zulu natal", "KwaZulu-Natal",
            "eastern cape", "Eastern Cape",
            "northern cape", "Northern Cape",
            "western cape", "Western Cape",
            "north west", "North West");

    /** Values that mean "no province data" rather than a province actually called Unknown. */
    private static final List<String> PROVINCE_PLACEHOLDERS = List.of("", "n/a", "na", "unknown", "tbd", "-");

    /** Accepted true/false spellings, normalized to lower case before lookup. */
    private static final Map<String, Boolean> BOOLEAN_VALUES = Map.ofEntries(
            Map.entry("y", true), Map.entry("yes", true), Map.entry("1", true), Map.entry("true", true),
            Map.entry("n", false), Map.entry("no", false), Map.entry("0", false), Map.entry("false", false));

    /**
     * Active flags fall back to this when a value is unreadable. A hub that is merely
     * <em>unclear</em> is left active, matching the OR rule used for duplicates: a parsing
     * gap should not silently stop parcels moving.
     */
    private static final boolean ACTIVE_DEFAULT = true;

    private CsvCleaner() {
    }

    /** Reads and cleans the CSV at {@code path}. */
    public static List<Hub> clean(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return clean(reader);
        }
    }

    /**
     * Reads and cleans a CSV from an open {@link Reader}, which is closed by the caller.
     *
     * <p>Rows are read positionally: the header names are ignored and columns are taken in
     * document order ({@code hub_id, province, sorting_center, active}). That keeps the parser
     * working if the export ever reorders or renames its header, which is the usual cause of
     * a legacy pipeline breaking silently.
     */
    public static List<Hub> clean(Reader reader) throws IOException {
        List<String[]> rows;
        try (com.opencsv.CSVReader csv =
                     new CSVReaderBuilder(reader).withSkipLines(1).build()) {
            rows = new ArrayList<>(csv.readAll());
        } catch (CsvException e) {
            throw new IOException("Malformed CSV: " + e.getMessage(), e);
        }

        // Keyed by normalized id so H-500 and h-500 are recognized as the same hub.
        Map<String, Hub> byId = new LinkedHashMap<>();

        for (String[] row : rows) {
            if (row.length < 4 || isBlankRow(row)) {
                continue;
            }

            String hubId = normalizeHubId(row[0]);
            if (hubId.isEmpty()) {
                continue;
            }

            Hub incoming = new Hub(
                    hubId,
                    normalizeProvince(row[1]),
                    normalizeSortingCenter(row[2]),
                    normalizeBoolean(row[3]));

            byId.merge(hubId, incoming, CsvCleaner::mergeHubs);
        }

        return List.copyOf(byId.values());
    }

    /**
     * Resolves two records for the same hub id.
     *
     * @return the surviving record: a real province beats a placeholder, and {@code active}
     *         is true if either record said so
     */
    static Hub mergeHubs(Hub existing, Hub incoming) {
        String province = isPlaceholderProvince(existing.province())
                ? incoming.province()
                : existing.province();

        String sortingCenter = existing.sortingCenter().isEmpty()
                ? incoming.sortingCenter()
                : existing.sortingCenter();

        boolean active = existing.active() || incoming.active();

        return new Hub(existing.hubId(), province, sortingCenter, active);
    }

    private static boolean isBlankRow(String[] row) {
        for (String cell : row) {
            if (!collapseSpaces(cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Hub ids are compared and reported upper-cased, so {@code h-501} and {@code H-501} match. */
    private static String normalizeHubId(String raw) {
        return collapseSpaces(raw).toUpperCase(Locale.ROOT);
    }

    /** Trims, collapses internal runs of spaces, and resolves known regional spellings. */
    private static String normalizeProvince(String raw) {
        String cleaned = collapseSpaces(raw);
        if (isPlaceholderProvince(cleaned)) {
            return "Unknown";
        }
        String canonical = PROVINCE_ALIASES.get(cleaned.toLowerCase(Locale.ROOT));
        if (canonical != null) {
            return canonical;
        }
        return toTitleCase(cleaned);
    }

    private static boolean isPlaceholderProvince(String value) {
        return PROVINCE_PLACEHOLDERS.contains(value.toLowerCase(Locale.ROOT));
    }

    private static String normalizeSortingCenter(String raw) {
        return toTitleCase(collapseSpaces(raw));
    }

    /**
     * Maps the accepted spellings onto a single boolean, falling back to {@link #ACTIVE_DEFAULT}
     * for values outside the set rather than rejecting the row.
     */
    private static boolean normalizeBoolean(String raw) {
        Boolean parsed = BOOLEAN_VALUES.get(collapseSpaces(raw).toLowerCase(Locale.ROOT));
        return parsed == null ? ACTIVE_DEFAULT : parsed;
    }

    /** Trims the field and squeezes internal whitespace runs down to a single space. */
    static String collapseSpaces(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    private static String toTitleCase(String value) {
        if (value.isEmpty()) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean startOfWord = true;
        for (char c : value.toCharArray()) {
            if (Character.isWhitespace(c)) {
                startOfWord = true;
                out.append(c);
            } else if (startOfWord) {
                out.append(Character.toUpperCase(c));
                startOfWord = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
