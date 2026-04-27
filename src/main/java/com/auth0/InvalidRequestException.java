package com.auth0;

/**
 * Represents an error occurred while executing a request against the Auth0 Authentication API.
 * Extends {@link IdentityVerificationException} and is thrown for OAuth protocol errors,
 * invalid state parameters, or missing required tokens.
 *
 * @example Handle an invalid request exception
 * try {
 *     Tokens tokens = controller.handle(request, response);
 * } catch (InvalidRequestException e) {
 *     // OAuth error or invalid/missing request parameters
 *     String errorCode = e.getCode();
 *     String message   = e.getMessage();
 * }
 */
@SuppressWarnings("WeakerAccess")
public class InvalidRequestException extends IdentityVerificationException {
    static final String INVALID_STATE_ERROR = "a0.invalid_state";
    static final String MISSING_ID_TOKEN = "a0.missing_id_token";
    static final String MISSING_ACCESS_TOKEN = "a0.missing_access_token";
    static final String DEFAULT_DESCRIPTION = "The request contains an error";

    InvalidRequestException(String code, String description) {
        this(code, description, null);
    }

    InvalidRequestException(String code, String description, Throwable cause) {
        super(code, description != null ? description : DEFAULT_DESCRIPTION, cause);
    }

    /**
     * Getter for the description of the error.
     *
     * @return the error description if available, null otherwise.
     * @deprecated use {@link #getMessage()}
     */
    @Deprecated
    public String getDescription() {
        return getMessage();
    }

}
