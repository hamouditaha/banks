package com.banks.fraud.account.exception;

public class AccountNotActiveException extends RuntimeException {
    public AccountNotActiveException(String accountId) {
        super("Account is not active: " + accountId);
    }
}
