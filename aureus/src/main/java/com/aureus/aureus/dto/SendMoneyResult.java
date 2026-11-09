package com.aureus.aureus.dto;

public class SendMoneyResult {

    private SendMoneyResponse response;
    private boolean replayed;


    // Constructor

    public SendMoneyResult(
            SendMoneyResponse response,
            boolean replayed) {

        this.response = response;
        this.replayed = replayed;
    }


    // Getters

    public SendMoneyResponse getResponse() {
        return response;
    }

    public boolean isReplayed() {
        return replayed;
    }
}