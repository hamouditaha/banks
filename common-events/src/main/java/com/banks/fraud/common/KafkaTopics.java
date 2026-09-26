package com.banks.fraud.common;

/**
 * Central registry of Kafka topic names used to carry SAGA commands and replies
 * between the orchestrator and the participant services.
 */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    public static final String DEBIT_COMMAND = "account.debit.command";
    public static final String DEBIT_REPLY = "account.debit.reply";

    public static final String CREDIT_COMMAND = "account.credit.command";
    public static final String CREDIT_REPLY = "account.credit.reply";

    public static final String REFUND_COMMAND = "account.refund.command";
    public static final String REFUND_REPLY = "account.refund.reply";

    public static final String FRAUD_CHECK_COMMAND = "fraud.check.command";
    public static final String FRAUD_CHECK_REPLY = "fraud.check.reply";

    public static final String TRANSACTION_OUTCOME = "transaction.outcome";
}
