package com.example.ledger.exception;

public class LedgerException extends RuntimeException {
    private final int status;
    private final String code;

    private LedgerException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }

    public static LedgerException badRequest(String code, String message) { return new LedgerException(400, code, message); }
    public static LedgerException missing(String message) { return new LedgerException(404, "NOT_FOUND", message); }
    public static LedgerException conflict(String code, String message) { return new LedgerException(409, code, message); }
    public static LedgerException unprocessable(String code, String message) { return new LedgerException(422, code, message); }
}
