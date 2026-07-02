package com.dyno.presenter;

import com.dyno.history.RunHistorySummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RunLabelsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void usesServerDisplayIdWhenPresent() throws Exception {
        RunHistorySummaryDto run = mapper.readValue(
            "{\"run_id\":7,\"license_plate\":\"ABC 123\",\"run_no\":3,\"display_id\":\"ABC 123-3\"}",
            RunHistorySummaryDto.class);
        assertEquals("ABC 123-3", RunLabels.displayId(run));
    }

    @Test
    public void buildsPlateRunNoWhenServerDisplayIdMissing() {
        assertEquals("ABC 123-2", RunLabels.displayId(null, "ABC 123", 2L, 7L));
    }

    @Test
    public void fallsBackToRunIdWithoutPlate() {
        assertEquals("RUN-00007", RunLabels.displayId(null, null, null, 7L));
        assertEquals("RUN-00007", RunLabels.displayId(" ", "ABC", null, 7L));
    }

    @Test
    public void deserializesCustomerFields() throws Exception {
        RunHistorySummaryDto run = mapper.readValue(
            "{\"run_id\":1,\"customer_name\":\"Somchai\",\"customer_phone\":\"081-234\",\"notes\":\"retune\"}",
            RunHistorySummaryDto.class);
        assertEquals("Somchai", run.getCustomerName());
        assertEquals("081-234", run.getCustomerPhone());
        assertEquals("retune", run.getNotes());
    }
}
