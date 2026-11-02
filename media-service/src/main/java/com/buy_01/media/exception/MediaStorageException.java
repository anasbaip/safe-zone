package com.buy_01.media.exception;

/** Provider responses can contain account details; expose only this controlled message. */
public class MediaStorageException extends RuntimeException {
    public MediaStorageException(Throwable cause) {
        super("Image storage is unavailable. Please retry or contact support.", cause);
    }
}
