package dev.olatool.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fault table: 194 known faults, matched to CAN signal names.
 *
 * <p>A signal name like {@code BMS_Precharge_Fail} is turned into
 * {@code bmsPrechargeFail}, which is the id the table uses. A signal that is 1
 * or more means the fault is active.
 */
public final class Faults {

    public static final String CRITICAL = "CRITICAL";
    public static final String WARNING = "WARNING";
    public static final String INFO = "INFO";

    /** One row of the table. */
    public static final class Fault {
        public final String id;
        public final String title;
        public final String description;
        public final String severity;
        public final String component;
        public final boolean fixable;
        public final String postFixMessage;

        Fault(String id, String title, String description, String severity,
              String component, boolean fixable, String postFixMessage) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.severity = severity == null ? INFO : severity;
            this.component = component == null ? "OTHER" : component;
            this.fixable = fixable;
            this.postFixMessage = postFixMessage;
        }

        public boolean critical() {
            return CRITICAL.equals(severity);
        }

        public boolean warning() {
            return WARNING.equals(severity);
        }

        @Override
        public String toString() {
            return title + " (" + component + ", " + severity + ")";
        }
    }

    private final Map<String, Fault> byId = new LinkedHashMap<>();

    private Faults() {
    }

    /** Parses the {@code {"faults": [...]}} document. */
    public static Faults parse(String json) {
        Faults table = new Faults();
        Map<String, Object> root = Json.object(json);
        for (Object o : Json.array(root, "faults")) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = Json.cast(o);
            String id = Json.string(m, "id", "");
            if (id.isEmpty()) {
                continue;
            }
            table.byId.put(id, new Fault(
                    id,
                    Json.string(m, "title", id),
                    Json.string(m, "description", ""),
                    Json.string(m, "severity", INFO),
                    Json.string(m, "component", "OTHER"),
                    Json.flag(m, "fixable"),
                    Json.string(m, "postFixMessage", null)));
        }
        return table;
    }

    public int size() {
        return byId.size();
    }

    public Fault get(String id) {
        return id == null ? null : byId.get(id);
    }

    public List<Fault> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public List<Fault> fixable() {
        List<Fault> out = new ArrayList<>();
        for (Fault f : byId.values()) {
            if (f.fixable) {
                out.add(f);
            }
        }
        return out;
    }

    /** Looks a signal up by its exact name, then by the camelCase form. */
    public Fault forSignal(String signalName) {
        if (signalName == null || signalName.isEmpty()) {
            return null;
        }
        Fault exact = byId.get(signalName);
        if (exact != null) {
            return exact;
        }
        return byId.get(camelCase(signalName));
    }

    /** {@code BMS_Precharge_Fail} becomes {@code bmsPrechargeFail}. */
    public static String camelCase(String text) {
        String[] parts = text.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(text.length());
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (first) {
                sb.append(part);
                first = false;
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part, 1, part.length());
                }
            }
        }
        return sb.length() == 0 ? text : sb.toString();
    }
}
