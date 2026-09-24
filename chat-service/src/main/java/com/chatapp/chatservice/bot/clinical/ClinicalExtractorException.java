package com.chatapp.chatservice.bot.clinical;

public class ClinicalExtractorException extends RuntimeException {
    public ClinicalExtractorException(String message) {
        super(message);
    }

    public ClinicalExtractorException(String message, Throwable cause) {
        super(message, cause);
    }
}
