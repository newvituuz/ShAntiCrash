package br.com.singlehope.shanticrash.configuration;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {
    @Test
    void legacyCodesFromDataNeverBreakParsing() {
        String escaped = Messages.escape("canal§cvermelho<red>");
        assertFalse(escaped.contains("§"));
        Component component = assertDoesNotThrow(() -> Messages.parse("<gray>Canal: <white>" + escaped));
        assertTrue(Messages.plain(component).contains("canal?cvermelho<red>"));
    }

    @Test
    void parseFallsBackInsteadOfThrowing() {
        assertNotNull(assertDoesNotThrow(() -> Messages.parse("EndereÃ§os <white>0 KiB")));
        assertNotNull(assertDoesNotThrow(() -> Messages.parse("§aVerde &cvermelho")));
        assertNotNull(assertDoesNotThrow(() -> Messages.parse("<gray>sem fechar <bold")));
    }
}
