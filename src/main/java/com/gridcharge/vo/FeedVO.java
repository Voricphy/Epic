package com.gridcharge.vo;

public record FeedVO(
        java.util.List<com.gridcharge.entity.StationSlot> records, long nextMax, int nextOffset) {}
