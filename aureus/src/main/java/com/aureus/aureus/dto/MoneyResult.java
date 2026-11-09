package com.aureus.aureus.dto;

public class MoneyResult {

    private final MoneyResponse response;
    private final boolean replayed;

    public MoneyResult(MoneyResponse response, boolean replayed) {
        this.response = response;
        this.replayed = replayed;
    }

    public MoneyResponse getResponse() {
        return response;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
