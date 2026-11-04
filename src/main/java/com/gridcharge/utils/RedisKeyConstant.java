package com.gridcharge.utils;

public final class RedisKeyConstant {
    private RedisKeyConstant() {}

    public static final String STREAM = "gc:{reservation}:stream";
    public static final String GROUP = "reservation-workers";
    public static final String GEO = "gc:station:geo";
    public static final String STATION_BLOOM = "gc:{station-bloom}:ids:v1";
    public static final String STATION_BLOOM_READY = "gc:{station-bloom}:ready:v1";
    public static final String STATION_BLOOM_LOCK = "gc:{station-bloom}:lock:v1";

    public static final String LOGIN_USER_KEY = "gc:auth:token:";
    // 登录态有效期，单位：分钟；登录和后续续期使用同一个值。
    public static final long LOGIN_USER_TTL = 30;

    public static String slot(long id) {
        return "gc:{reservation}:slot:" + id;
    }

    public static String slotLock(long id) {
        return "gc:lock:slot:" + id;
    }

    public static String station(long id) {
        return "gc:cache:station:" + id;
    }
}
