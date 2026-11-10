package br.com.singlehope.shanticrash.flood;

/** Immutable, validated window rules loaded once during configuration refresh. */
public final class WindowRuleSet {
    public enum Action { DROP, FLAG, COUNT }

    public static final WindowRuleSet EMPTY = new WindowRuleSet(new Rule[0]);
    public final Rule[] rules;

    public WindowRuleSet(Rule[] rules) {
        if (rules == null || rules.length > 4) {
            throw new IllegalArgumentException("máximo de quatro janelas por grupo");
        }
        this.rules = rules.clone();
    }

    public static final class Rule {
        public final int intervalMs;
        public final int maxEvents;
        public final int minBytes;
        public final int maxBytes;
        public final Action action;

        public Rule(int intervalMs, int maxEvents, int minBytes, int maxBytes, Action action) {
            if (intervalMs < 50 || intervalMs > 60_000 || maxEvents < 1 || maxEvents > 512
                    || minBytes < 0 || maxBytes < minBytes || action == null) {
                throw new IllegalArgumentException("regra de janela inválida");
            }
            this.intervalMs = intervalMs;
            this.maxEvents = maxEvents;
            this.minBytes = minBytes;
            this.maxBytes = maxBytes;
            this.action = action;
        }

        public boolean applies(int bytes) {
            return bytes >= minBytes && bytes <= maxBytes;
        }
    }
}
