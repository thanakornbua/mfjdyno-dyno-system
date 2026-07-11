package com.dyno.calibration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class CalibrationApiContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void activeCalibrationShapeParses() throws Exception {
        String json =
            "{"
                + "\"profile\":{" 
                + "\"profile_id\":7,"
                + "\"name\":\"Track profile\","
                + "\"created_at_ms\":1000,"
                + "\"updated_at_ms\":2000,"
                + "\"is_active\":true,"
                + "\"roller_diameter_m\":0.318,"
                + "\"encoder_pulses_per_rev\":60.0,"
                + "\"roller_inertia_kg_m2\":3.5,"
                + "\"sample_window_ms\":100,"
                + "\"engine_pulses_per_rev_hint\":1.0,"
                + "\"engine_rpm_scale\":1.0,"
                + "\"notes\":\"baseline\""
                + "},"
                + "\"validation\":{" 
                + "\"is_valid\":true,"
                + "\"warnings\":[\"roller_diameter_m: outside the typical dyno range\"],"
                + "\"errors\":[]"
                + "}"
                + "}";

        CalibrationResponseDto response = MAPPER.readValue(json, CalibrationResponseDto.class);

        assertNotNull(response.getProfile());
        assertEquals(Long.valueOf(7L), response.getProfile().getProfileId());
        assertEquals("Track profile", response.getProfile().getName());
        assertTrue(response.getValidation().isValid());
        assertEquals(1, response.getValidation().getWarnings().size());
        assertNull(response.getActivated());
    }

    @Test
    public void calibrationProfileListShapeParses() throws Exception {
        String json =
            "["
                + "{"
                + "\"profile_id\":1,"
                + "\"name\":\"Default bootstrap profile\","
                + "\"created_at_ms\":1000,"
                + "\"updated_at_ms\":1000,"
                + "\"is_active\":true,"
                + "\"roller_diameter_m\":0.318,"
                + "\"encoder_pulses_per_rev\":60.0,"
                + "\"roller_inertia_kg_m2\":3.5,"
                + "\"sample_window_ms\":100"
                + "},"
                + "{"
                + "\"profile_id\":2,"
                + "\"name\":\"Large roller profile\","
                + "\"created_at_ms\":2000,"
                + "\"updated_at_ms\":2000,"
                + "\"is_active\":false,"
                + "\"roller_diameter_m\":1.2,"
                + "\"encoder_pulses_per_rev\":48.0,"
                + "\"roller_inertia_kg_m2\":4.0,"
                + "\"sample_window_ms\":100"
                + "}"
                + "]";

        List<CalibrationProfileDto> profiles = MAPPER.readValue(
            json,
            new TypeReference<List<CalibrationProfileDto>>() { }
        );

        assertEquals(2, profiles.size());
        assertTrue(Boolean.TRUE.equals(profiles.get(0).getActive()));
        assertFalse(Boolean.TRUE.equals(profiles.get(1).getActive()));
    }

    @Test
    public void activationRequestSerializesExpectedShape() throws Exception {
        String json = MAPPER.writeValueAsString(new ActivateCalibrationRequestDto(Long.valueOf(42L)));
        assertEquals("{\"profile_id\":42}", json);
    }

    @Test
    public void upsertRequestSerializesExpectedShape() throws Exception {
        String json = MAPPER.writeValueAsString(new CalibrationUpsertRequestDto(
            "Street tune",
            Double.valueOf(0.318),
            Double.valueOf(60.0),
            Double.valueOf(3.5),
            Long.valueOf(100L),
            Double.valueOf(1.0),
            Double.valueOf(1.0),
            "baseline",
            Boolean.TRUE
        ));
        assertTrue(json.contains("\"name\":\"Street tune\""));
        assertTrue(json.contains("\"roller_diameter_m\":0.318"));
        assertTrue(json.contains("\"activate_after_save\":true"));
    }

    @Test
    public void duplicateRequestSerializesExpectedShape() throws Exception {
        String json = MAPPER.writeValueAsString(new DuplicateCalibrationProfileRequestDto("Street tune-1", Boolean.FALSE));
        assertEquals("{\"name\":\"Street tune-1\",\"activate_after_save\":false}", json);
    }

    @Test
    public void eventListShapeParses() throws Exception {
        String json =
            "["
                + "{"
                + "\"event_id\":11,"
                + "\"profile_id\":7,"
                + "\"event_type\":\"updated\","
                + "\"created_at_ms\":2000,"
                + "\"summary\":\"Updated profile Track profile\","
                + "\"previous_values_json\":{\"name\":\"Track profile\"},"
                + "\"new_values_json\":{\"name\":\"Track profile v2\"}"
                + "}"
                + "]";

        List<CalibrationProfileEventDto> events = MAPPER.readValue(
            json,
            new TypeReference<List<CalibrationProfileEventDto>>() { }
        );

        assertEquals(1, events.size());
        assertEquals("updated", events.get(0).getEventType());
        assertEquals("Track profile v2", events.get(0).getNewValuesJson().get("name").asText());
    }

    @Test
    public void setupStatusShapeParsesPasswordSetTrue() throws Exception {
        SetupStatusDto status = MAPPER.readValue("{\"password_set\":true}", SetupStatusDto.class);
        assertTrue(status.isPasswordSet());
    }

    @Test
    public void setupStatusShapeParsesPasswordSetFalse() throws Exception {
        SetupStatusDto status = MAPPER.readValue("{\"password_set\":false}", SetupStatusDto.class);
        assertFalse(status.isPasswordSet());
    }

    @Test
    public void serialDevicesShapeParses() throws Exception {
        String json =
            "{"
                + "\"devices\":["
                + "{\"path\":\"/dev/ttyUSB0\",\"label\":\"usb-Silicon_Labs_CP2102N\",\"is_esp32_guess\":true},"
                + "{\"path\":\"/dev/ttyUSB1\",\"label\":\"/dev/ttyUSB1\",\"is_esp32_guess\":false}"
                + "],"
                + "\"read_serial_port\":\"/dev/ttyUSB0\","
                + "\"flash_serial_port\":\"/dev/ttyUSB1\""
                + "}";

        SerialDevicesDto devices = MAPPER.readValue(json, SerialDevicesDto.class);

        assertEquals(2, devices.getDevices().size());
        assertEquals("/dev/ttyUSB0", devices.getDevices().get(0).getPath());
        assertTrue(devices.getDevices().get(0).isEsp32Guess());
        assertFalse(devices.getDevices().get(1).isEsp32Guess());
        assertEquals("/dev/ttyUSB0", devices.getReadSerialPort());
        assertEquals("/dev/ttyUSB1", devices.getFlashSerialPort());
    }

    @Test
    public void dependenciesShapeParses() throws Exception {
        String json =
            "{"
                + "\"dependencies\":["
                + "{"
                + "\"name\":\"arduino_cli\","
                + "\"category\":\"flash-toolchain\","
                + "\"required\":false,"
                + "\"status\":\"missing\","
                + "\"detail\":\"'arduino-cli' was not found on PATH\","
                + "\"remediation\":\"Install arduino-cli\","
                + "\"blocks_flashing\":true"
                + "},"
                + "{"
                + "\"name\":\"serial_device\","
                + "\"category\":\"device\","
                + "\"required\":true,"
                + "\"status\":\"ok\","
                + "\"detail\":\"1 serial device(s) detected\","
                + "\"remediation\":\"\","
                + "\"blocks_flashing\":false"
                + "}"
                + "]"
                + "}";

        DependencyStatusDto status = MAPPER.readValue(json, DependencyStatusDto.class);

        assertEquals(2, status.getDependencies().size());
        DependencyDto arduino = status.getDependencies().get(0);
        assertEquals("arduino_cli", arduino.getName());
        assertEquals("flash-toolchain", arduino.getCategory());
        assertFalse(arduino.isRequired());
        assertTrue(arduino.isMissing());
        assertFalse(arduino.isOk());
        assertTrue(arduino.blocksFlashing());

        DependencyDto serial = status.getDependencies().get(1);
        assertEquals("device", serial.getCategory());
        assertTrue(serial.isRequired());
        assertTrue(serial.isOk());
        assertFalse(serial.blocksFlashing());
    }

    @Test
    public void flashStatusShapeParsesTerminalStates() throws Exception {
        FlashStatusDto running = MAPPER.readValue(
            "{\"state\":\"running\",\"log\":\"compiling...\",\"port\":\"/dev/ttyUSB1\"}",
            FlashStatusDto.class);
        assertTrue(running.isRunning());
        assertFalse(running.isTerminal());
        assertEquals("compiling...", running.getLog());

        FlashStatusDto success = MAPPER.readValue("{\"state\":\"success\",\"log\":\"done\"}", FlashStatusDto.class);
        assertTrue(success.isSuccess());
        assertTrue(success.isTerminal());

        FlashStatusDto error = MAPPER.readValue("{\"state\":\"error\",\"log\":\"boom\"}", FlashStatusDto.class);
        assertTrue(error.isError());
        assertTrue(error.isTerminal());

        FlashStatusDto idle = MAPPER.readValue("{\"state\":\"idle\",\"log\":\"\"}", FlashStatusDto.class);
        assertFalse(idle.isTerminal());
    }

    @Test
    public void lockExceptionRecognizesSetupRequiredStatus() {
        CalibrationApiClient.LockException setupRequired =
            new CalibrationApiClient.LockException(409, "setup_required");
        assertTrue(setupRequired.isSetupRequired());

        CalibrationApiClient.LockException wrongPassword =
            new CalibrationApiClient.LockException(401, "wrong password");
        assertFalse(wrongPassword.isSetupRequired());
    }

    @Test
    public void draftValidatorFlagsBlankName() {
        CalibrationValidationDto validation = CalibrationDraftValidator.validate(
            new CalibrationUpsertRequestDto(
                "   ",
                Double.valueOf(0.318),
                Double.valueOf(60.0),
                Double.valueOf(3.5),
                Long.valueOf(100L),
                null,
                null,
                null,
                Boolean.FALSE
            )
        );

        assertFalse(validation.isValid());
        assertEquals("name: must not be blank", validation.getErrors().get(0));
    }
}
