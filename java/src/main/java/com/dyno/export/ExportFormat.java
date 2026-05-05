package com.dyno.export;

public enum ExportFormat {
    PDF("PDF Report", ".pdf"),
    PNG("Chart Image (PNG)", ".png"),
    CSV("Frame Data (CSV)", ".csv"),
    JSON("Run Data (JSON)", ".json");

    private final String displayName;
    private final String extension;

    ExportFormat(String displayName, String extension) {
        this.displayName = displayName;
        this.extension = extension;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getExtension() {
        return extension;
    }
}
