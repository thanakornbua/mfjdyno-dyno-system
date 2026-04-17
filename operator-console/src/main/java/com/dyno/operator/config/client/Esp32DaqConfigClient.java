package com.dyno.operator.config.client;

import com.dyno.operator.config.model.Esp32DaqConfigResponseDto;
import com.dyno.operator.config.model.Esp32DaqConfigUpdateRequestDto;
import java.util.concurrent.CompletableFuture;

public interface Esp32DaqConfigClient {
    CompletableFuture<Esp32DaqConfigResponseDto> loadCurrentConfig();

    CompletableFuture<Esp32DaqConfigResponseDto> submitConfig(Esp32DaqConfigUpdateRequestDto request);
}
