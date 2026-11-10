package br.com.singlehope.shanticrash.packet;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.metrics.ProfileSession;

/**
 * Runs the chain of a packet: stops at the first cancellation and keeps the failure of a check from
 * affecting the others or the connection.
 */
public final class PacketPipeline {
    /** Receives failures that are bugs and not problems with the packet. */
    public interface ErrorHandler {
        void onError(Check check, Throwable error);
    }

    private PacketPipeline() {
    }

    public static void run(PacketContext context, Check[] chain, Check malformed, ErrorHandler errors) {
        ProfileSession profile = context.data.profile();
        boolean timed = profile != null && context.profileSampled;
        for (Check check : chain) {
            long start = timed ? System.nanoTime() : 0L;
            try {
                check.run(context);
            } catch (MalformedPacketException e) {
                malformed.reject(context, check.id() + ": " + e.getMessage());
                context.cancel();
            } catch (StackOverflowError e) {
                malformed.reject(context, check.id() + ": estrutura recursiva");
                context.cancel();
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable t) {
                errors.onError(check, t);
            }
            if (profile != null) {
                profile.checkRun(check.index(), timed ? System.nanoTime() - start : -1L);
            }
            if (context.data.isClosing()) {
                context.cancel();
            }
            if (context.isCancelled()) {
                if (context.cancelledBy == null) {
                    context.cancelledBy = check.id();
                }
                return;
            }
        }
    }
}
