package com.auth0;

/**
 * Thrown when an error occurs while verifying the identity tokens returned
 * by Auth0 during authentication. This is the base exception for all
 * authentication errors in this SDK.
 *
 * @example Handle identity verification errors
 * try {
 *     Tokens tokens = controller.handle(request, response);
 * } catch (IdentityVerificationException e) {
 *     if (e.isAPIError()) {
 *         // Auth0 API returned an error response
 *     } else if (e.isJWTError()) {
 *         // Token signature or claims validation failed
 *     }
 * }
 */
@SuppressWarnings("WeakerAccess")
public class IdentityVerificationException extends Exception {

    static final String API_ERROR = "a0.api_error";
    static final String JWT_MISSING_PUBLIC_KEY_ERROR = "a0.missing_jwt_public_key_error";
    static final String JWT_VERIFICATION_ERROR = "a0.invalid_jwt_error";
    private final String code;

    IdentityVerificationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Getter for the code of the error.
     *
     * @return the error code.
     */
    public String getCode() {
        return code;
    }

    public boolean isAPIError() {
        return API_ERROR.equals(code);
    }

    public boolean isJWTError() {
        return JWT_MISSING_PUBLIC_KEY_ERROR.equals(code) || JWT_VERIFICATION_ERROR.equals(code);
    }
}
