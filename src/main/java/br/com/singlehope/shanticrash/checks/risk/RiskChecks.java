package br.com.singlehope.shanticrash.checks.risk;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;

public final class RiskChecks {
    private RiskChecks() {
    }

    public static final class Critical extends Check {
        public Critical() {
            super("risk", "critical", "Risco crítico: violações estruturais repetidas na mesma conexão.");
        }

        @Override
        public boolean isPassive() {
            return true;
        }

        @Override
        public boolean isBehavioral() {
            return true;
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().cancel(false).punishAt(1.0D).actions(PunishmentAction.KICK);
        }

        @Override
        protected void process(PacketContext context) {
        }
    }
}
