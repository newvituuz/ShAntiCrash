package br.com.singlehope.shanticrash.regression;

import br.com.singlehope.shanticrash.flood.RollingWindowLimiter;
import br.com.singlehope.shanticrash.flood.WindowRuleSet;
import br.com.singlehope.shanticrash.flood.WindowRuleState;
import br.com.singlehope.shanticrash.utils.Utf8ByteLength;

/** Standalone tests: run with java -ea; no Minecraft server required. */
public final class StandaloneSecurityRegression {
    public static void main(String[] args) {
        RollingWindowLimiter limiter = new RollingWindowLimiter(2, 100);
        assert limiter.allow(1_000_000_000L);
        assert limiter.allow(1_010_000_000L);
        assert !limiter.allow(1_020_000_000L);
        assert limiter.allow(1_101_000_000L);
        WindowRuleSet rules = new WindowRuleSet(new WindowRuleSet.Rule[] {
            new WindowRuleSet.Rule(100, 1, 0, 1024, WindowRuleSet.Action.DROP),
            new WindowRuleSet.Rule(100, 1, 1025, Integer.MAX_VALUE, WindowRuleSet.Action.FLAG)
        });
        WindowRuleState state = new WindowRuleState();
        assert state.account(rules, 100, 1_000_000_000L) == null;
        assert state.account(rules, 100, 1_010_000_000L) == WindowRuleSet.Action.DROP;
        assert state.account(rules, 2000, 1_020_000_000L) == null;
        assert state.account(rules, 2000, 1_030_000_000L) == WindowRuleSet.Action.FLAG;
        assert Utf8ByteLength.upTo("abc", 10) == 3;
        assert Utf8ByteLength.upTo("é", 10) == 2;
        assert Utf8ByteLength.upTo("😀", 10) == 4;
        assert Utf8ByteLength.upTo("😀", 3) == 4;
        assert Utf8ByteLength.upTo("\uD800", 10) == 1;
        System.out.println("StandaloneSecurityRegression: PASS");
    }
}
