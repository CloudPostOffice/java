package com.cloudpostoffice.exceptions;

/** Thrown when authentication with the CloudPostOffice API fails. */
public class AuthenticationException extends CloudPostOfficeException {

    private final int status;

    public AuthenticationException(String message, int httpStatus) {
        super(message);
        this.status = httpStatus;
    }

    public AuthenticationException(String message) {
        super(message);
        this.status = -1;
    }

    /** HTTP status code returned by the API, or {@code -1} if unavailable. */
    public int getStatus() {
        return status;
    }
}
