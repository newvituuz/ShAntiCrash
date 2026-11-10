package br.com.singlehope.shanticrash.packet;

/**
 * A frame does not follow the wire format.
 *
 * <p>Stack traces are disabled: an attacker can trigger this at will, so it must cost no more than a
 * regular branch.
 */
public final class MalformedPacketException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MalformedPacketException(String reason) {
        super(reason, null, false, false);
    }
}
