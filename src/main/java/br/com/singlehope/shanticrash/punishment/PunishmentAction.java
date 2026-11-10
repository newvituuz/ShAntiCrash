package br.com.singlehope.shanticrash.punishment;

/** What happens when a check reaches its punishment threshold. */
public enum PunishmentAction {
    /** Nothing beyond recording the violation. */
    NONE,
    /** Sends an alert even if the check's own alerts are below their minimum VL. */
    ALERT,
    /** Only the packet is discarded, which is already handled by the check's {@code cancel} option. */
    CANCEL,
    /** Closes the connection without a message, the cheapest answer during an attack. */
    DROP,
    /** Disconnects with a message. */
    KICK,
    /** Runs the configured console commands through the global scheduler. */
    COMMAND
}
