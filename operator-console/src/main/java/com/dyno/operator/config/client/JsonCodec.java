package com.dyno.operator.config.client;

public interface JsonCodec {
    String write(Object value);

    <T> T read(String json, Class<T> type);
}
