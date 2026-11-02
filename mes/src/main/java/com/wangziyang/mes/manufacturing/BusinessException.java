package com.wangziyang.mes.manufacturing;

public class BusinessException extends RuntimeException {
    private final String status;

    public BusinessException(String status, String message) {
        super(message);
        this.status = status;
    }

    public String getStatus() { return status; }
}
