package com.procurepal_services.stock_bridge_api.transfer;

/**
 * A transfer change its current state rules out, with the sentence saying why and what to do instead:
 * already received (and by whom), cancelled, more reported than was sent. Maps to 409 - the phone keeps
 * the write as "needs you" with this sentence.
 */
public class TransferRefusedException extends RuntimeException {

    public TransferRefusedException(String message) {
        super(message);
    }
}
