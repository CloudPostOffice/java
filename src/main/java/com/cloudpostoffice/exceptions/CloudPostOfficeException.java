package com.cloudpostoffice.exceptions;

/** Base type for all CloudPostOffice SDK exceptions. */
public class CloudPostOfficeException extends Exception {

    public CloudPostOfficeException(String message) {
        super(message);
    }

    public CloudPostOfficeException(String message, Throwable cause) {
        super(message, cause);
    }
}
