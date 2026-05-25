package com.cloudpostoffice.exceptions;

/** Thrown when the MQTT connection to the broker times out. */
public class ConnectionTimeoutException extends CloudPostOfficeException {

    public ConnectionTimeoutException(String postboxId) {
        super("MQTT connection timed out for postbox \"" + postboxId + "\"");
    }
}
