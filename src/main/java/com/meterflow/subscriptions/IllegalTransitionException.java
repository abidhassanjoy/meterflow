package com.meterflow.subscriptions;

/** এই অবস্থায় এই কাজ চলে না (যেমন বাতিল subscription আবার চালু করা)। */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(String message) {
        super(message);
    }
}