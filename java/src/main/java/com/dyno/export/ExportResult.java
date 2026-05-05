package com.dyno.export;

import java.nio.file.Path;
import java.util.List;

public final class ExportResult {
    private final List<Path> exportedFiles;
    private final List<String> errors;

    public ExportResult(List<Path> exportedFiles, List<String> errors) {
        this.exportedFiles = exportedFiles;
        this.errors = errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<Path> getExportedFiles() {
        return exportedFiles;
    }

    public List<String> getErrors() {
        return errors;
    }
}
