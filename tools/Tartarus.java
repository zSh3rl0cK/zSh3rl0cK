import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Tartarus: draws the last 52 weeks of GitHub contributions as a tower in the style of the
 * Persona 3 Reload menus, one floor per week and one block per day. Busier days stick further
 * out of the wall.
 *
 *   java tools/Tartarus.java                fetch with $GITHUB_TOKEN and write assets/tartarus.svg
 *   java tools/Tartarus.java --data f.json  render from a saved GraphQL response
 *   java tools/Tartarus.java headings       regenerate the README section headings
 *
 * Single file, no dependencies: runs with the plain `java` launcher (JDK 21+). Display text is
 * converted to vector outlines from tools/fonts, so it looks the same in every browser.
 */
public class Tartarus {

    static final String USER = System.getenv().getOrDefault("GITHUB_USER", "zSh3rl0cK");
    static final Path OUT = Path.of("assets", "tartarus.svg");
    static final Path HEADINGS = Path.of("assets", "headings");
    static final Path FONT_FILE = Path.of("tools", "fonts", "BarlowCondensed-BlackItalic.ttf");
    static final Set<String> HIDDEN_LANGS = Set.of("Jupyter Notebook");
    static final int FLOORS = 52;
    static final String[] BLOCKS = {"Thebel", "Arqa", "Yabbashah", "Tziah", "Harabah", "Adamah"};
    static final boolean SOUTHERN_SKY = true; // draw the moon as it looks from Brazil

    // Persona 3 Reload pause menu palette
    static final String INK = "#050a1f", WHITE = "#ffffff", PALE = "#eef6ff", CYAN = "#5ff3ff",
            BLUE = "#1f5bff", RED = "#ff2742", YELLOW = "#f5e663";
    // Block faces per contribution level: top, facade, side
    static final String[][] FACES = {
            {"#06185f", "#04124b", "#030d3a"},
            {"#2a6bff", "#1f55e0", "#1844b8"},
            {"#3fa9ff", "#2f8ae6", "#2470c4"},
            {"#86f2ff", "#55d8f5", "#3db6da"},
            {"#ffffff", "#d9f9ff", "#a6ecff"},
    };
    static final String[] TODAY = {"#ff5a6e", "#ff2742", "#c9162e"};
    // How far each level's block sticks out of the wall, in tiles
    static final double[] DEPTH = {1.0, 1.6, 2.2, 2.8, 3.4};

    static final String SANS = "'Segoe UI','Helvetica Neue',Ubuntu,Arial,sans-serif";

    static final String QUERY = """
            query($login: String!) {
              user(login: $login) {
                contributionsCollection {
                  totalCommitContributions
                  totalPullRequestContributions
                  totalPullRequestReviewContributions
                  totalIssueContributions
                  totalRepositoryContributions
                  contributionCalendar {
                    weeks { contributionDays { date contributionCount contributionLevel } }
                  }
                }
                repositories(first: 100, ownerAffiliations: OWNER, isFork: false, privacy: PUBLIC) {
                  nodes { languages(first: 10, orderBy: {field: SIZE, direction: DESC}) { edges { size node { name color } } } }
                }
              }
            }
            """;
    static final List<String> LEVELS = List.of("NONE", "FIRST_QUARTILE", "SECOND_QUARTILE", "THIRD_QUARTILE", "FOURTH_QUARTILE");

    record Day(LocalDate date, int count, int level) {}
    record Lang(String name, String color, long size) {}
    record Data(List<List<Day>> floors, int commits, int pullRequests, int reviews, int issues, int newRepos,
                List<Lang> langs, long langTotal) {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length > 0 && args[0].equals("headings")) {
            writeHeadings();
            return;
        }
        String json;
        if (args.length > 1 && args[0].equals("--data")) {
            json = Files.readString(Path.of(args[1]));
        } else {
            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isBlank()) {
                System.err.println("Set GITHUB_TOKEN or pass --data <file.json>");
                System.exit(1);
            }
            json = fetch(token);
        }
        Data data = parse(json);
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, new Tower(data).render());
        System.out.println("wrote " + OUT);
    }

    // ---------------------------------------------------------------- data

    static String fetch(String token) throws IOException, InterruptedException {
        String body = "{\"query\":" + quote(QUERY) + ",\"variables\":{\"login\":" + quote(USER) + "}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/graphql"))
                .header("Authorization", "bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().contains("\"errors\"")) {
            throw new IOException("GitHub API " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    static Data parse(String json) {
        Map<?, ?> user = map(get(Json.parse(json), "data", "user"));
        Map<?, ?> collection = map(user.get("contributionsCollection"));

        List<List<Day>> weeks = new ArrayList<>();
        for (Object week : list(get(collection, "contributionCalendar", "weeks"))) {
            List<Day> days = new ArrayList<>();
            for (Object d : list(map(week).get("contributionDays"))) {
                Map<?, ?> day = map(d);
                days.add(new Day(LocalDate.parse((String) day.get("date")), num(day.get("contributionCount")),
                        Math.max(0, LEVELS.indexOf((String) day.get("contributionLevel")))));
            }
            weeks.add(days);
        }
        // The calendar covers 53 weeks and the oldest one is partial, so keep the last 52
        List<List<Day>> floors = weeks.subList(Math.max(0, weeks.size() - FLOORS), weeks.size());

        Map<String, Lang> langs = new HashMap<>();
        for (Object repo : list(get(user, "repositories", "nodes"))) {
            for (Object e : list(get(repo, "languages", "edges"))) {
                Map<?, ?> edge = map(e), node = map(edge.get("node"));
                String name = (String) node.get("name");
                if (HIDDEN_LANGS.contains(name)) continue;
                String color = node.get("color") instanceof String c ? c : PALE;
                langs.merge(name, new Lang(name, color, ((Number) edge.get("size")).longValue()),
                        (a, b) -> new Lang(a.name(), a.color(), a.size() + b.size()));
            }
        }
        long langTotal = langs.values().stream().mapToLong(Lang::size).sum();
        List<Lang> top = langs.values().stream().sorted(Comparator.comparingLong(Lang::size).reversed()).limit(6).toList();

        return new Data(floors,
                num(collection.get("totalCommitContributions")),
                num(collection.get("totalPullRequestContributions")),
                num(collection.get("totalPullRequestReviewContributions")),
                num(collection.get("totalIssueContributions")),
                num(collection.get("totalRepositoryContributions")),
                top, langTotal);
    }

    // ---------------------------------------------------------------- tower

    static final class Tower {
        static final int W = 880, H = 640;
        static final double S = 14, COS = Math.cos(Math.PI / 6), SIN = 0.5;  // isometric tile
        static final double FLOOR = 9, CUBE = 7.5;                           // floor pitch and block height, px
        static final double OX = 226, OY = 522;                              // tower origin
        static final double SYNODIC = 29.530588853;

        final Data data;
        final List<Day> days;
        final Type type = new Type();
        final StringBuilder svg = new StringBuilder();

        Tower(Data data) {
            this.data = data;
            this.days = data.floors().stream().flatMap(List::stream).toList();
        }

        String render() {
            LocalDate first = days.get(0).date(), last = days.get(days.size() - 1).date();
            svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(W).append("\" height=\"").append(H)
                    .append("\" viewBox=\"0 0 ").append(W).append(' ').append(H).append("\" role=\"img\" aria-labelledby=\"title\">")
                    .append("<title id=\"title\">Tartarus: ").append(total()).append(" contributions from ")
                    .append(first).append(" to ").append(last).append(", one floor per week</title>");
            style();
            svg.append("<g clip-path=\"url(#frame)\">");
            sea();
            strip();
            moon(last);
            blocks();
            tower(last);
            status();
            commands();
            party();
            floor();
            hints(last);
            svg.append("</g>").append(type.defs()).append("</svg>\n");
            return svg.toString();
        }

        void style() {
            svg.append("<style>")
                    .append(".cap{font:italic 600 10.5px ").append(SANS).append(";fill:").append(CYAN).append('}')
                    .append(".say{font:italic 600 15px ").append(SANS).append(";fill:").append(WHITE).append('}')
                    .append(".fl{animation:lit .5s ease-out both}")
                    .append("@keyframes lit{from{opacity:0}to{opacity:1}}")
                    .append("@media (prefers-reduced-motion:reduce){.fl{animation:none}}")
                    .append("</style><defs>")
                    .append("<linearGradient id=\"sea\" x1=\"0\" y1=\"0\" x2=\".35\" y2=\"1\">")
                    .append("<stop offset=\"0\" stop-color=\"#3a9dff\"/><stop offset=\".22\" stop-color=\"#1652f2\"/>")
                    .append("<stop offset=\".62\" stop-color=\"#0b2fd0\"/><stop offset=\"1\" stop-color=\"#061470\"/></linearGradient>")
                    .append("<linearGradient id=\"fadeG\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#fff\"/>")
                    .append("<stop offset=\".5\" stop-color=\"#fff\" stop-opacity=\".3\"/><stop offset=\"1\" stop-color=\"#fff\" stop-opacity=\"0\"/></linearGradient>")
                    .append("<mask id=\"fade\"><rect width=\"").append(W).append("\" height=\"").append(H).append("\" fill=\"url(#fadeG)\"/></mask>")
                    // Caustics: keep only the thin contour where the noise crosses its middle value
                    .append("<filter id=\"caustics\" x=\"0\" y=\"0\" width=\"").append(W).append("\" height=\"").append(H)
                    .append("\" filterUnits=\"userSpaceOnUse\"><feTurbulence type=\"fractalNoise\" baseFrequency=\".012 .03\" numOctaves=\"2\" seed=\"3\"/>")
                    .append("<feColorMatrix type=\"matrix\" values=\"0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 1 0 0 0 0\"/>")
                    .append("<feComponentTransfer><feFuncA type=\"table\" tableValues=\"0 0 0 0 0 0 0 0 0 0 .9 0 0 0 0 0 0 0 0 0 0\"/></feComponentTransfer></filter>")
                    .append("<radialGradient id=\"halo\"><stop offset=\".5\" stop-color=\"#fff\" stop-opacity=\".35\"/>")
                    .append("<stop offset=\"1\" stop-color=\"#fff\" stop-opacity=\"0\"/></radialGradient>")
                    .append("<radialGradient id=\"orb\" cx=\".4\" cy=\".35\"><stop offset=\"0\" stop-color=\"#4f95ff\"/>")
                    .append("<stop offset=\"1\" stop-color=\"").append(BLUE).append("\"/></radialGradient>")
                    .append("<linearGradient id=\"enemy\" x1=\"0\" x2=\"1\"><stop offset=\"0\" stop-color=\"#a50d22\"/>")
                    .append("<stop offset=\".35\" stop-color=\"").append(RED).append("\"/><stop offset=\"1\" stop-color=\"#c4142c\"/></linearGradient>")
                    .append("<filter id=\"glow\" x=\"-50%\" y=\"-50%\" width=\"200%\" height=\"200%\"><feGaussianBlur stdDeviation=\"1.8\" result=\"b\"/>")
                    .append("<feMerge><feMergeNode in=\"b\"/><feMergeNode in=\"SourceGraphic\"/></feMerge></filter>")
                    .append("<clipPath id=\"frame\"><rect width=\"").append(W).append("\" height=\"").append(H).append("\" rx=\"8\"/></clipPath>")
                    .append("<clipPath id=\"band\"><polygon points=\"0,0 84,0 60,").append(H).append(" 0,").append(H).append("\"/></clipPath>")
                    .append("</defs>");
        }

        // Underwater blue with light rippling in from the surface
        void sea() {
            svg.append("<rect width=\"").append(W).append("\" height=\"").append(H).append("\" fill=\"url(#sea)\"/>")
                    .append("<rect width=\"").append(W).append("\" height=\"").append(H)
                    .append("\" filter=\"url(#caustics)\" mask=\"url(#fade)\" opacity=\".36\"/>");
            double[][] rays = {{300, 60, -40}, {470, 90, 10}, {700, 50, 120}};
            for (double[] r : rays) {
                svg.append("<polygon fill=\"#fff\" opacity=\".07\" points=\"").append(f(r[0])).append(",0 ")
                        .append(f(r[0] + r[1])).append(",0 ").append(f(r[0] + r[1] + r[2] + 80)).append(',').append(H).append(' ')
                        .append(f(r[0] + r[2])).append(',').append(H).append("\"/>");
            }
        }

        // The giant cropped name on a white band, like the side text of the pause menu
        void strip() {
            svg.append("<polygon fill=\"").append(PALE).append("\" points=\"0,0 84,0 60,").append(H).append(" 0,").append(H).append("\"/>")
                    .append("<g clip-path=\"url(#band)\"><g transform=\"translate(70 654) rotate(-90)\">");
            type.text(svg, "TARTARUS", 0, 0, 150, INK, 0, -1);
            svg.append("</g></g>")
                    .append("<polygon fill=\"").append(CYAN).append("\" points=\"84,0 92,0 68,").append(H).append(" 60,").append(H).append("\"/>");
        }

        // The tower stands in front of the moon, in tonight's real phase
        void moon(LocalDate date) {
            double cx = OX + 50, cy = 86, r = 50;
            double theta = 2 * Math.PI * moonAge(date) / SYNODIC;
            double rx = Math.abs(Math.cos(theta)) * r;
            boolean waxing = theta < Math.PI, crescent = Math.cos(theta) > 0;
            int limb = waxing ? 1 : 0, terminator = waxing == crescent ? 0 : 1;
            circle(cx, cy, r * 1.9, "url(#halo)");
            circle(cx, cy, r, "#0a2a9e");
            if (SOUTHERN_SKY) svg.append("<g transform=\"translate(").append(f(2 * cx)).append(" 0) scale(-1 1)\">");
            svg.append("<path fill=\"").append(PALE).append("\" d=\"M").append(f(cx)).append(',').append(f(cy - r))
                    .append(" A").append(f(r)).append(',').append(f(r)).append(" 0 0 ").append(limb).append(' ').append(f(cx)).append(',').append(f(cy + r))
                    .append(" A").append(f(rx)).append(',').append(f(r)).append(" 0 0 ").append(terminator).append(' ').append(f(cx)).append(',').append(f(cy - r))
                    .append("Z\"/>");
            if (SOUTHERN_SKY) svg.append("</g>");
        }

        // Six labelled blocks along the left side, oldest (Thebel) at the bottom
        void blocks() {
            double x = OX - DEPTH[4] * S * COS - 12;
            double shift = (OX - x) * SIN / COS;  // follow the isometric slope down to the label column
            for (int b = 0; b < BLOCKS.length; b++) {
                int from = blockStart(b), to = Math.min(blockStart(b + 1), data.floors().size()) - 1;
                if (from > to) continue;
                double top = OY - (to * FLOOR + CUBE) + shift + 1.5, bottom = OY - from * FLOOR + shift - 1.5, mid = (top + bottom) / 2;
                svg.append("<path fill=\"none\" stroke=\"").append(CYAN).append("\" stroke-opacity=\".7\" stroke-width=\"1.5\" d=\"M")
                        .append(f(x - 4)).append(',').append(f(top)).append(" H").append(f(x)).append(" V").append(f(bottom))
                        .append(" H").append(f(x - 4)).append("\"/>");
                int sum = 0;
                for (int k = from; k <= to; k++) sum += weekTotal(k);
                LocalDate start = data.floors().get(from).get(0).date();
                List<Day> lastWeek = data.floors().get(to);
                LocalDate end = lastWeek.get(lastWeek.size() - 1).date();
                shadowed(BLOCKS[b].toUpperCase(), x - 10, mid + 1, 17, WHITE, 1, 2);
                text(x - 10, mid + 15, "cap", "end", months(start, end) + " · " + sum);
            }
        }

        // Painter's order: bottom floor first, then each floor from Sunday to Saturday
        void tower(LocalDate today) {
            List<List<Day>> floors = data.floors();
            for (int k = 0; k < floors.size(); k++) {
                Day[] slot = new Day[7];
                for (Day d : floors.get(k)) slot[d.date().getDayOfWeek().getValue() % 7] = d;
                double zb = k * FLOOR, zt = zb + CUBE;
                svg.append("<g class=\"fl\" style=\"animation-delay:").append(250 + k * 32).append("ms\">");
                for (int i = 0; i < 7; i++) {
                    Day d = slot[i];
                    if (d == null) {  // days of this week that haven't happened yet
                        outline(i, zb, zt);
                        continue;
                    }
                    double depth = DEPTH[d.level()];
                    String[] c = d.date().equals(today) ? TODAY : FACES[d.level()];
                    boolean glow = d.level() == 4;
                    boolean covered = i < 6 && slot[i + 1] != null && DEPTH[slot[i + 1].level()] >= depth;
                    if (glow) svg.append("<g filter=\"url(#glow)\">");
                    if (!covered) face(c[2], i + 1, 0, zb, i + 1, depth, zb, i + 1, depth, zt, i + 1, 0, zt);
                    face(c[1], i, depth, zb, i + 1, depth, zb, i + 1, depth, zt, i, depth, zt);
                    face(c[0], i, 0, zt, i + 1, 0, zt, i + 1, depth, zt, i, depth, zt);
                    if (glow) svg.append("</g>");
                }
                svg.append("</g>");
            }
        }

        // The party status row: the profile as the leader, HP for contributions, SP for the streak
        void status() {
            int[] streaks = streaks();
            double y = 28;
            para(352, y, 500, 52, INK, .88);
            svg.append("<rect x=\"334\" y=\"").append(f(y)).append("\" width=\"20\" height=\"52\" fill=\"").append(RED).append("\"/>")
                    .append("<g transform=\"translate(349 ").append(f(y + 49)).append(") rotate(-90)\">");
            type.text(svg, "LEADER", 0, 0, 13, WHITE, 0, 2);
            svg.append("</g>");
            type.text(svg, USER, 372, y + 36, 30, WHITE, 0, 0);
            type.text(svg, "/", 552, y + 38, 30, CYAN, 0, 0);
            gauge("Lv", Integer.toString(data.floors().size()), 572, y, WHITE, 1, null);
            gauge("HP", String.format("%04d", total()), 636, y, CYAN, 1, CYAN);
            gauge("SP", String.format("%03d", streaks[0]), 748, y, YELLOW,
                    streaks[1] == 0 ? 0 : streaks[0] / (double) streaks[1], YELLOW);
            text(354, y + 72, "cap", "start", "HP contributions · SP day streak, best " + streaks[1] + " · Lv floors climbed");
        }

        void gauge(String label, String value, double x, double y, String color, double share, String bar) {
            type.text(svg, label, x, y + 36, 14, color, 0, 0);
            double w = type.text(svg, value, x + 20, y + 36, 30, color, 0, 0);
            if (bar == null) return;
            para(x + 4, y + 42, w + 14, 4, "#16285c", 1);
            para(x + 4, y + 42, Math.max(2, (w + 14) * share), 4, bar, 1);
        }

        // Activity as the battle command wheel; the biggest number takes the ATTACK slot
        void commands() {
            double cx = 430, cy = 236, r = 72;
            circle(cx, cy, r + 7, PALE, .25);
            circle(cx, cy, r, "url(#orb)");
            String[][] buttons = {{"P", "0", "-31"}, {"I", "-31", "0"}, {"R", "31", "0"}, {"C", "0", "31"}};
            for (String[] b : buttons) {
                double bx = cx + Double.parseDouble(b[1]), by = cy + Double.parseDouble(b[2]);
                circle(bx, by, 13, WHITE, 1);
                type.text(svg, b[0], bx + 1, by + 6, 17, BLUE, .5, 0);
            }
            command("PULL REQUESTS", data.pullRequests(), cx - 112, cy - 50, 28);
            command("REVIEWS", data.reviews(), cx + 52, cy + 4, 24);
            command("ISSUES", data.issues(), cx - 112, cy + 36, 26);
            command("COMMITS", data.commits(), cx - 36, cy + 92, 42);
        }

        void command(String label, int value, double x, double y, double size) {
            svg.append("<g transform=\"rotate(-6 ").append(f(x)).append(' ').append(f(y)).append(")\">");
            double w = shadowed(label, x, y, size, WHITE, 0, 3);
            type.outlined(svg, String.format("%03d", value), x + w + 6, y, size * .55, CYAN, 0, INK, 3);
            svg.append("</g>");
        }

        // Languages as the party list: the language color is the member's tab
        void party() {
            double x = 652, y = 138;
            type.text(svg, "<", x - 40, y - 8, 18, RED, 0, 0);
            type.text(svg, "LANGUAGES", x - 24, y - 8, 18, WHITE, 0, 1);
            type.text(svg, ">", x + 64, y - 8, 18, RED, 0, 0);
            double top = data.langs().isEmpty() ? 1 : data.langs().get(0).size();
            for (int i = 0; i < data.langs().size(); i++) {
                Lang l = data.langs().get(i);
                double ry = y + i * 31;
                para(x, ry, 200, 25, INK, .88);
                para(x + 1, ry, 9, 25, l.color(), 1);
                String tag = i == 0 ? "LEADER" : i < 4 ? "PARTY" : i == 4 ? "NAVI" : null;
                if (tag != null) {
                    String bg = i == 0 ? RED : i == 4 ? CYAN : INK, fg = i == 4 ? INK : WHITE;
                    para(x - 52, ry + 5, 48, 16, bg, 1);
                    type.text(svg, tag, x - 26, ry + 18, 13, fg, .5, 1);
                }
                type.text(svg, l.name(), x + 20, ry + 18, 17, WHITE, 0, 0);
                type.text(svg, String.format(Locale.ROOT, "%.1f", 100.0 * l.size() / data.langTotal()), x + 190, ry + 18, 19, CYAN, 1, 0);
                para(x + 20, ry + 21, Math.max(2, 120 * l.size() / top), 2, CYAN, .8);
            }
        }

        // The current floor as an enemy banner, with a navigator line underneath
        void floor() {
            int current = data.floors().size() - 1, busiest = 0;
            for (int k = 0; k < data.floors().size(); k++) if (weekTotal(k) > weekTotal(busiest)) busiest = k;
            List<Day> best = data.floors().get(busiest);
            double x = 352, y = 420;
            para(x + 5, y + 5, 330, 40, INK, .7);
            para(x, y, 330, 40, "url(#enemy)", 1);
            type.text(svg, "FLOOR " + (current + 1) + " · " + blockName(current), x + 26, y + 30, 28, WHITE, 0, 1);

            para(x, y + 60, 44, 18, CYAN, 1);
            type.text(svg, "NAVI", x + 22, y + 74, 14, INK, .5, 1);
            say(x + 56, y + 75, weekTotal(current) + " contributions on this floor so far.");
            say(x + 56, y + 99, "Busiest floor: " + (busiest + 1) + ", " + range(best.get(0).date(), best.get(best.size() - 1).date())
                    + ", with " + weekTotal(busiest) + ". Keep climbing!");
        }

        void say(double x, double y, String line) {
            svg.append("<text x=\"").append(f(x + 1.5)).append("\" y=\"").append(f(y + 1.5)).append("\" class=\"say\" fill-opacity=\".5\" style=\"fill:")
                    .append(INK).append("\">").append(esc(line)).append("</text>");
            text(x, y, "say", "start", line);
        }

        // Controller hints along the bottom, like "(A) Confirm (B) Close"
        void hints(LocalDate last) {
            double age = moonAge(last);
            String[][] hints = {
                    {"A", "Updated " + last},
                    {"B", data.newRepos() + " new repos"},
                    {"Y", moonName(age) + " " + Math.round(50 * (1 - Math.cos(2 * Math.PI * age / SYNODIC))) + "%"},
            };
            double x = 852, y = 616;
            for (int i = hints.length - 1; i >= 0; i--) {
                double w = type.width(hints[i][1], 15, 0);
                type.text(svg, hints[i][1], x, y, 15, WHITE, 1, 0);
                x -= w + 14;
                circle(x, y - 5, 8.5, WHITE, 1);
                type.text(svg, hints[i][0], x + .5, y - 0.5, 12, BLUE, .5, 0);
                x -= 22;
            }
        }

        // ------------------------------------------------------------ pieces

        double shadowed(String s, double x, double y, double size, String fill, double anchor, double offset) {
            type.text(svg, s, x + offset, y + offset, size, INK, anchor, 0);
            return type.text(svg, s, x, y, size, fill, anchor, 0);
        }

        int[] streaks() {
            int longest = 0, run = 0;
            for (Day d : days) {
                run = d.count() > 0 ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            int current = 0;
            for (int i = days.size() - 1; i >= 0; i--) {
                if (days.get(i).count() > 0) current++;
                else if (i == days.size() - 1) continue;  // today isn't over yet
                else break;
            }
            return new int[]{current, longest};
        }

        int total() {
            return days.stream().mapToInt(Day::count).sum();
        }

        int weekTotal(int floor) {
            return data.floors().get(floor).stream().mapToInt(Day::count).sum();
        }

        int blockStart(int b) {
            return (int) Math.round(b * FLOORS / (double) BLOCKS.length);
        }

        String blockName(int floor) {
            int b = 0;
            while (b + 1 < BLOCKS.length && floor >= blockStart(b + 1)) b++;
            return BLOCKS[b].toUpperCase();
        }

        double px(double x, double y) {
            return OX + (x - y) * S * COS;
        }

        double py(double x, double y, double z) {
            return OY + (x + y) * S * SIN - z;
        }

        void face(String fill, double... p) {
            svg.append("<polygon fill=\"").append(fill).append("\" points=\"");
            for (int j = 0; j < p.length; j += 3) {
                if (j > 0) svg.append(' ');
                svg.append(f(px(p[j], p[j + 1]))).append(',').append(f(py(p[j], p[j + 1], p[j + 2])));
            }
            svg.append("\"/>");
        }

        // Dashed facade and top of a block for the days still to come this week
        void outline(int i, double zb, double zt) {
            svg.append("<path fill=\"none\" stroke=\"#fff\" stroke-opacity=\".45\" stroke-width=\"1\" stroke-dasharray=\"2 2\" d=\"M")
                    .append(f(px(i, 1))).append(',').append(f(py(i, 1, zb)))
                    .append(" L").append(f(px(i + 1, 1))).append(',').append(f(py(i + 1, 1, zb)))
                    .append(" L").append(f(px(i + 1, 1))).append(',').append(f(py(i + 1, 1, zt)))
                    .append(" L").append(f(px(i + 1, 0))).append(',').append(f(py(i + 1, 0, zt)))
                    .append(" L").append(f(px(i, 0))).append(',').append(f(py(i, 0, zt)))
                    .append(" L").append(f(px(i, 1))).append(',').append(f(py(i, 1, zt))).append(" Z\"/>");
        }

        void para(double x, double y, double w, double h, String fill, double opacity) {
            Tartarus.para(svg, x, y, w, h, fill, opacity);
        }

        void circle(double cx, double cy, double r, String fill) {
            circle(cx, cy, r, fill, 1);
        }

        void circle(double cx, double cy, double r, String fill, double opacity) {
            svg.append("<circle cx=\"").append(f(cx)).append("\" cy=\"").append(f(cy)).append("\" r=\"").append(f(r))
                    .append("\" fill=\"").append(fill).append('"');
            if (opacity < 1) svg.append(" opacity=\"").append(f(opacity)).append('"');
            svg.append("/>");
        }

        void text(double x, double y, String cls, String anchor, String s) {
            svg.append("<text x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" class=\"").append(cls).append('"');
            if (!anchor.equals("start")) svg.append(" text-anchor=\"").append(anchor).append('"');
            svg.append('>').append(esc(s)).append("</text>");
        }

        // Days since the new moon of 2000-01-06 18:14 UTC, at 03:00 UTC (midnight in Brazil)
        static double moonAge(LocalDate date) {
            double days = date.toEpochDay() + 0.125 - 10962.76;
            return ((days % SYNODIC) + SYNODIC) % SYNODIC;
        }

        static String moonName(double age) {
            String[] names = {"New moon", "Waxing crescent", "First quarter", "Waxing gibbous",
                    "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"};
            return names[(int) Math.floor(age / SYNODIC * 8 + 0.5) % 8];
        }

        static String month(LocalDate d) {
            return d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        }

        static String months(LocalDate a, LocalDate b) {
            return month(a).equals(month(b)) ? month(a) : month(a) + "–" + month(b);
        }

        static String range(LocalDate a, LocalDate b) {
            return month(a).equals(month(b))
                    ? month(a) + " " + a.getDayOfMonth() + "–" + b.getDayOfMonth()
                    : month(a) + " " + a.getDayOfMonth() + " – " + month(b) + " " + b.getDayOfMonth();
        }
    }

    // ---------------------------------------------------------------- headings

    static void writeHeadings() throws IOException {
        String[][] headings = {
                {"about", "cat about.md"}, {"stack", "ls stack/"}, {"projects", "ls projects/"},
                {"tartarus", "java Tartarus.java"}, {"spotify", "playerctl metadata"}, {"contact", "cat contact.txt"}};
        Files.createDirectories(HEADINGS);
        for (String[] h : headings) Files.writeString(HEADINGS.resolve(h[0] + ".svg"), heading(h[1]));
        System.out.println("wrote " + headings.length + " headings to " + HEADINGS);
    }

    // The command as the selected item of the pause menu: a blue prompt tag and a tilted red banner
    static String heading(String command) {
        Type type = new Type();
        StringBuilder body = new StringBuilder();
        String label = command.toUpperCase(Locale.ROOT);
        double size = 30, textW = type.width(label, size, 0);
        double bx = 44, bw = textW + 40, h = 36, skew = h * 0.36;

        para(body, 4, 16, 30, 26, BLUE, 1);
        type.text(body, "$", 23, 36, 22, WHITE, .5, 0);
        body.append("<g transform=\"rotate(-2 ").append(f(bx + bw / 2)).append(" 30)\">");
        para(body, bx + 6, 18, bw, h, INK, 1);
        para(body, bx + bw * .3, 41, bw * .62, 11, CYAN, 1);
        para(body, bx, 12, bw, h, RED, 1);
        type.outlined(body, label, bx + skew / 2 + 20, 40, size, WHITE, 0, INK, 2.5);
        body.append("</g>");

        double w = Math.ceil(bx + bw + skew + 14);
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + f(w) + "\" height=\"58\" viewBox=\"0 0 " + f(w) + " 58\""
                + " role=\"img\" aria-label=\"$ " + esc(command) + "\">" + body + type.defs() + "</svg>\n";
    }

    // A parallelogram leaning right, the slanted shape of Persona 3 menus
    static void para(StringBuilder svg, double x, double y, double w, double h, String fill, double opacity) {
        double o = h * 0.36;
        svg.append("<polygon fill=\"").append(fill).append('"');
        if (opacity < 1) svg.append(" opacity=\"").append(f(opacity)).append('"');
        svg.append(" points=\"").append(f(x + o)).append(',').append(f(y)).append(' ').append(f(x + w + o)).append(',').append(f(y)).append(' ')
                .append(f(x + w)).append(',').append(f(y + h)).append(' ').append(f(x)).append(',').append(f(y + h)).append("\"/>");
    }

    // ---------------------------------------------------------------- type

    /**
     * Sets text in Barlow Condensed Black Italic as vector outlines. Each glyph is defined once per
     * document at 100 units and reused with <use>, so repeated letters cost a few bytes each.
     */
    static final class Type {
        static final float UNITS = 100f;
        static final FontRenderContext FRC = new FontRenderContext(null, true, true);
        static final Font FONT = load();

        final Map<Integer, String> ids = new HashMap<>();
        final StringBuilder defs = new StringBuilder();

        static Font load() {
            try (InputStream in = Files.newInputStream(FONT_FILE)) {
                return Font.createFont(Font.TRUETYPE_FONT, in)
                        .deriveFont(Map.of(TextAttribute.SIZE, UNITS, TextAttribute.KERNING, TextAttribute.KERNING_ON));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (FontFormatException e) {
                throw new IllegalStateException(FONT_FILE + " is not a TrueType font", e);
            }
        }

        double width(String s, double size, double tracking) {
            GlyphVector gv = layout(s);
            int n = gv.getNumGlyphs();
            return (gv.getGlyphPosition(n).getX() + (n - 1) * tracking) * size / UNITS;
        }

        /** Draws s with its baseline at (x, y); anchor 0 is start, .5 middle, 1 end. Returns the width. */
        double text(StringBuilder svg, String s, double x, double y, double size, String fill, double anchor, double tracking) {
            return draw(svg, s, x, y, size, fill, anchor, tracking, null);
        }

        /** Same as text, with a hard outline drawn behind the fill. */
        double outlined(StringBuilder svg, String s, double x, double y, double size, String fill, double anchor, String stroke, double strokeWidth) {
            double k = size / UNITS;
            return draw(svg, s, x, y, size, fill, anchor, 0, " stroke=\"" + stroke + "\" stroke-width=\"" + f(strokeWidth / k)
                    + "\" stroke-linejoin=\"round\" paint-order=\"stroke\"");
        }

        private double draw(StringBuilder svg, String s, double x, double y, double size, String fill, double anchor, double tracking, String extra) {
            GlyphVector gv = layout(s);
            int n = gv.getNumGlyphs();
            double k = size / UNITS, w = (gv.getGlyphPosition(n).getX() + (n - 1) * tracking) * k;
            svg.append("<g transform=\"translate(").append(f(x - w * anchor)).append(' ').append(f(y)).append(") scale(")
                    .append(String.format(Locale.ROOT, "%.4f", k)).append(")\" fill=\"").append(fill).append('"');
            if (extra != null) svg.append(extra);
            svg.append('>');
            for (int i = 0; i < n; i++) {
                String id = glyph(gv.getGlyphCode(i));
                if (id.isEmpty()) continue;
                double gx = gv.getGlyphPosition(i).getX() + i * tracking;
                svg.append("<use href=\"#").append(id).append('"');
                if (Math.abs(gx) > 0.05) svg.append(" x=\"").append(f(gx)).append('"');
                svg.append("/>");
            }
            svg.append("</g>");
            return w;
        }

        private GlyphVector layout(String s) {
            char[] chars = s.toCharArray();
            return FONT.layoutGlyphVector(FRC, chars, 0, chars.length, Font.LAYOUT_LEFT_TO_RIGHT);
        }

        private String glyph(int code) {
            String id = ids.get(code);
            if (id != null) return id;
            String d = path(FONT.createGlyphVector(FRC, new int[]{code}).getOutline());
            id = d.isEmpty() ? "" : "g" + code;
            if (!d.isEmpty()) defs.append("<path id=\"").append(id).append("\" d=\"").append(d).append("\"/>");
            ids.put(code, id);
            return id;
        }

        String defs() {
            return "<defs>" + defs + "</defs>";
        }

        static String path(Shape shape) {
            StringBuilder b = new StringBuilder();
            double[] c = new double[6];
            for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
                switch (it.currentSegment(c)) {
                    case PathIterator.SEG_MOVETO -> b.append('M').append(f(c[0])).append(' ').append(f(c[1]));
                    case PathIterator.SEG_LINETO -> b.append('L').append(f(c[0])).append(' ').append(f(c[1]));
                    case PathIterator.SEG_QUADTO -> b.append('Q').append(f(c[0])).append(' ').append(f(c[1])).append(' ')
                            .append(f(c[2])).append(' ').append(f(c[3]));
                    case PathIterator.SEG_CUBICTO -> b.append('C').append(f(c[0])).append(' ').append(f(c[1])).append(' ')
                            .append(f(c[2])).append(' ').append(f(c[3])).append(' ').append(f(c[4])).append(' ').append(f(c[5]));
                    case PathIterator.SEG_CLOSE -> b.append('Z');
                }
            }
            return b.toString();
        }
    }

    // ---------------------------------------------------------------- helpers

    static Object get(Object o, String... path) {
        for (String key : path) o = map(o).get(key);
        return o;
    }

    static Map<?, ?> map(Object o) {
        return (Map<?, ?>) o;
    }

    static List<?> list(Object o) {
        return (List<?>) o;
    }

    static int num(Object o) {
        return ((Number) o).intValue();
    }

    static String f(double v) {
        long r = Math.round(v * 10);
        return r % 10 == 0 ? Long.toString(r / 10) : Double.toString(r / 10.0);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Just enough JSON to read the GraphQL response, so the script needs nothing but the JDK. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            return new Json(s).value();
        }

        private Object value() {
            ws();
            return switch (s.charAt(i)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                i++;  // ':'
                m.put(key, value());
                ws();
                if (s.charAt(i++) == '}') return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']') return l;
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : Double.parseDouble(n);
        }

        private Object literal(String word, Object v) {
            i += word.length();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
