package com.gridcharge.vo;

public record LoginVO(String token, long expiresInSeconds, com.gridcharge.entity.User user) {}
