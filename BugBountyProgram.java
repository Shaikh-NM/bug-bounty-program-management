import java.util.*;

public class BugBountyProgram {

    // ---------------- domain ----------------

    private static final class User {
        final String name;
        final String email;
        final String role;

        User(String name, String email, String role) {
            this.name = name;
            this.email = email;
            this.role = role;
        }

        boolean isAdmin() {
            return "admin".equalsIgnoreCase(role);
        }
    }

    private static final class BugReport {
        final String title;
        String description;
        String status = "Open";
        String severity;
        long bountyAmount = 0;
        final String reporterEmail;
        String assignedUser = "";
        final long createdTimeStamp;
        String closedTimeStamp = "";
        // Internal-only: never part of what the reporter sees.
        final List<String> comments = new ArrayList<>();

        BugReport(String title, String description, String severity, String reporterEmail, long createdTimeStamp) {
            this.title = title;
            this.description = description;
            this.severity = severity;
            this.reporterEmail = reporterEmail;
            this.createdTimeStamp = createdTimeStamp;
        }

        String format() {
            return "title=" + title
                    + ",status=" + status
                    + ",severity=" + severity
                    + ",bountyAmount=" + bountyAmount
                    + ",reporterEmail=" + reporterEmail
                    + ",assignedUser=" + assignedUser;
        }

        boolean isCompleted() {
            return "Closed".equals(status);
        }
    }

    // ---------------- constants ----------------

    private static final Set<String> SEVERITIES = Set.of("P0", "P1", "P2", "P3");

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "Open", Set.of("ReportReview"),
            "ReportReview", Set.of("Rejected", "Acknowledged"),
            "Rejected", Set.of("Closed"),
            "Acknowledged", Set.of("BountyReview"),
            "BountyReview", Set.of("BountyPaid"),
            "BountyPaid", Set.of("Closed"),
            "Closed", Set.of());

    // ---------------- state ----------------

    private final Map<String, User> users = new HashMap<>();
    // LinkedHashMap => listings come back in creation order (deterministic)
    private final Map<String, BugReport> reports = new LinkedHashMap<>();

    public BugBountyProgram(List<String> preloadedUsers) {
        for (String line : preloadedUsers) {
            Map<String, String> kv = new HashMap<>();
            for (String part : line.split(",")) {
                String[] p = splitKeyValue(part);
                if (p != null) kv.put(p[0], p[1]);
            }
            String name = kv.get("name");
            if (name != null && !name.isEmpty()) {
                users.put(name, new User(name, kv.getOrDefault("email", ""), kv.getOrDefault("role", "")));
            }
        }
    }

    // ---------------- create ----------------

    public synchronized String createBugReport(String title, String createdByUser, List<String> fields) {
        if (createdByUser == null || !users.containsKey(createdByUser)) return "ERROR_INVALID_USER";
        if (title != null && reports.containsKey(title)) return "ERROR_DUPLICATE_TITLE";
        if (title == null || title.isEmpty() || fields == null) return "ERROR_MISSING_REQUIRED_FIELDS";

        String description = null, severity = null, reporterEmail = null;
        for (String f : fields) {
            String[] kv = splitKeyValue(f);
            if (kv == null) return "ERROR_MISSING_REQUIRED_FIELDS";
            switch (kv[0]) {
                case "description": description = kv[1]; break;
                case "severity": severity = kv[1]; break;
                case "reporterEmail": reporterEmail = kv[1]; break;
                default: return "ERROR_MISSING_REQUIRED_FIELDS"; // key not allowed at create time
            }
        }
        if (isEmpty(description) || isEmpty(reporterEmail)
                || severity == null || !SEVERITIES.contains(severity)) {
            return "ERROR_MISSING_REQUIRED_FIELDS";
        }

        reports.put(title, new BugReport(title, description, severity, reporterEmail, System.currentTimeMillis()));
        return "OK";
    }

    // ---------------- update ----------------

    /**
     * All-or-nothing: every update is validated against a working copy, and the report
     * is changed only if all of them pass.
     * Extra code beyond the spec: ERROR_INVALID_INPUT for malformed pairs, unknown keys
     * or bad values (e.g. severity "P9", negative bountyAmount).
     */
    public synchronized String updateBugReport(String title, String updatedByUser, List<String> updates) {
        if (updatedByUser == null || !users.containsKey(updatedByUser)) return "ERROR_INVALID_USER";
        BugReport report = title == null ? null : reports.get(title);
        if (report == null) return "ERROR_NOT_FOUND";
        if (updates == null) return "ERROR_INVALID_INPUT";

        String description = report.description;
        String severity = report.severity;
        String status = report.status;
        long bounty = report.bountyAmount;
        String assigned = report.assignedUser;
        String closedTs = report.closedTimeStamp;
        List<String> newComments = new ArrayList<>();

        for (String u : updates) {
            String[] kv = splitKeyValue(u);
            if (kv == null) return "ERROR_INVALID_INPUT";
            String value = kv[1];
            switch (kv[0]) {
                case "description":
                    if (value.isEmpty()) return "ERROR_INVALID_INPUT";
                    description = value;
                    break;
                case "severity":
                    if (!SEVERITIES.contains(value)) return "ERROR_INVALID_INPUT";
                    severity = value;
                    break;
                case "bountyAmount":
                    try {
                        bounty = Long.parseLong(value.trim());
                    } catch (NumberFormatException e) {
                        return "ERROR_INVALID_INPUT";
                    }
                    if (bounty < 0) return "ERROR_INVALID_INPUT";
                    break;
                case "assignedUser":
                    if (!users.containsKey(value)) return "ERROR_INVALID_USER_FOR_ASSIGNMENT";
                    assigned = value;
                    break;
                case "comment":
                    if (value.isEmpty()) return "ERROR_INVALID_INPUT";
                    newComments.add(updatedByUser + ": " + value);
                    break;
                case "status":
                    // Only the user assigned *before* this call may move the status.
                    if (!updatedByUser.equals(report.assignedUser)) return "ERROR_FORBIDDEN";
                    if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(value)) {
                        return "ERROR_INVALID_TRANSITION";
                    }
                    status = value;
                    if ("Closed".equals(value)) closedTs = String.valueOf(System.currentTimeMillis());
                    break;
                default:
                    return "ERROR_INVALID_INPUT";
            }
        }

        report.description = description;
        report.severity = severity;
        report.status = status;
        report.bountyAmount = bounty;
        report.assignedUser = assigned;
        report.closedTimeStamp = closedTs;
        report.comments.addAll(newComments);
        return "OK";
    }

    // ---------------- delete ----------------

    public synchronized String deleteBugReport(String title, String deletedByUser) {
        User user = deletedByUser == null ? null : users.get(deletedByUser);
        if (user == null) return "ERROR_INVALID_USER";
        if (title == null || !reports.containsKey(title)) return "ERROR_NOT_FOUND";
        if (!user.isAdmin()) return "ERROR_FORBIDDEN";
        reports.remove(title);
        return "OK";
    }

    // ---------------- views ----------------

    public synchronized List<String> listBugReports(String viewType, String requestedByUser) {
        List<String> out = new ArrayList<>();
        if (requestedByUser == null || !users.containsKey(requestedByUser) || viewType == null) return out;

        for (BugReport r : reports.values()) {
            boolean include;
            switch (viewType) {
                case "all":
                    include = true;
                    break;
                case "assignedToMe":
                    include = requestedByUser.equals(r.assignedUser);
                    break;
                case "assignedToMe:completed":
                    include = requestedByUser.equals(r.assignedUser) && r.isCompleted();
                    break;
                case "assignedToMe:incomplete":
                    include = requestedByUser.equals(r.assignedUser) && !r.isCompleted();
                    break;
                default:
                    return new ArrayList<>(); // unknown view type
            }
            if (include) out.add(r.format());
        }
        return out;
    }

    /** Extra helper: internal comments, visible to employees only. */
    public synchronized List<String> getComments(String title, String requestedByUser) {
        if (requestedByUser == null || !users.containsKey(requestedByUser)) return new ArrayList<>();
        BugReport r = title == null ? null : reports.get(title);
        return r == null ? new ArrayList<>() : new ArrayList<>(r.comments);
    }

    // ---------------- helpers ----------------

    /** Splits "key=value" at the first '='; returns null if there is none. */
    private static String[] splitKeyValue(String s) {
        if (s == null) return null;
        int i = s.indexOf('=');
        if (i <= 0) return null;
        return new String[]{s.substring(0, i).trim(), s.substring(i + 1)};
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static void main(String[] args) {
        BugBountyProgram p = new BugBountyProgram(List.of(
                "name=user1,email=user1@fk.com,role=admin",
                "name=user2,email=user2@fk.com,role=agent"));

        System.out.println(p.createBugReport("Bug Title 1", "user1",
                List.of("description=Bug Description 1", "severity=P0", "reporterEmail=reporter.b1@email.com"))); // OK
        System.out.println(p.createBugReport("Bug Title 2", "user1",
                List.of("description=Bug Description 2", "severity=P0", "reporterEmail=reporter.b2@email.com"))); // OK

        System.out.println(p.updateBugReport("Bug Title 1", "user1", List.of("assignedUser=user1")));  // OK
        System.out.println(p.updateBugReport("Bug Title 2", "user1", List.of("assignedUser=user2")));  // OK
        System.out.println(p.updateBugReport("Bug Title 1", "user1", List.of("status=ReportReview"))); // OK
        System.out.println(p.updateBugReport("Bug Title 1", "user1", List.of("status=Acknowledged")));  // OK
        System.out.println(p.updateBugReport("Bug Title 1", "user1",
                List.of("status=BountyReview", "bountyAmount=1000", "comment=comment text 1")));       // OK

        System.out.println(p.deleteBugReport("Bug Title 2", "user1")); // OK
        System.out.println(p.listBugReports("all", "user1"));
        System.out.println(p.listBugReports("assignedToMe", "user1"));
        System.out.println(p.listBugReports("assignedToMe:incomplete", "user1"));
        System.out.println(p.listBugReports("assignedToMe:completed", "user1")); // []
        System.out.println(p.getComments("Bug Title 1", "user2"));
    }
}