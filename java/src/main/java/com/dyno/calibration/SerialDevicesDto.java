package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class SerialDevicesDto {
    private List<SerialDeviceDto> devices;

    @JsonProperty("read_serial_port")
    private String readSerialPort;

    @JsonProperty("flash_serial_port")
    private String flashSerialPort;

    public List<SerialDeviceDto> getDevices() {
        return devices == null ? List.of() : devices;
    }

    public String getReadSerialPort() {
        return readSerialPort;
    }

    public String getFlashSerialPort() {
        return flashSerialPort;
    }
}
