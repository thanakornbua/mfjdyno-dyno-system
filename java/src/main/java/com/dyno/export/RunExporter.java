package com.dyno.export;

import com.dyno.history.RunHistoryFrameDto;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.List;
import java.util.TreeMap;

public final class RunExporter {

    private static final DecimalFormat FMT_1D = new DecimalFormat("0.0");

    private RunExporter() {}

    public static final class RunMeta {
        public final String runName;
        public final String calibrationId;
        public final String recordedAt;
        public final Double peakHp;
        public final Double peakTorqueNm;

        public RunMeta(String runName, String calibrationId, String recordedAt,
                       Double peakHp, Double peakTorqueNm) {
            this.runName = runName;
            this.calibrationId = calibrationId;
            this.recordedAt = recordedAt;
            this.peakHp = peakHp;
            this.peakTorqueNm = peakTorqueNm;
        }
    }

    public static void exportCsv(List<RunHistoryFrameDto> frames, File outputFile, int rpmStep)
            throws IOException {
        TreeMap<Integer, RunHistoryFrameDto> buckets = bucket(frames, rpmStep);
        CSVFormat format = CSVFormat.Builder.create(CSVFormat.RFC4180).build();
        try (FileWriter fw = new FileWriter(outputFile, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(fw, format)) {
            printer.printRecord("rpm", "power_hp", "torque_nm", "speed_kmh", "afr", "lambda");
            for (RunHistoryFrameDto f : buckets.values()) {
                printer.printRecord(
                    fmt(f.getEngineRpm()),
                    fmt(f.getPowerHp()),
                    fmt(f.getTorqueNm()),
                    fmt(f.getSpeedKmh()),
                    fmt(f.getAfr()),
                    fmt(f.getLambda())
                );
            }
        }
    }

    public static void exportPdf(List<RunHistoryFrameDto> frames, RunMeta meta,
                                  File outputFile, int rpmStep) throws IOException {
        int effectiveStep = rpmStep;
        while (bucket(frames, effectiveStep).size() > 60 && effectiveStep < 5000) {
            effectiveStep += 50;
        }
        TreeMap<Integer, RunHistoryFrameDto> buckets = bucket(frames, effectiveStep);

        com.itextpdf.kernel.colors.DeviceRgb accent   = DynoPdfExporter.hexRgb("#1289CC");
        com.itextpdf.kernel.colors.DeviceRgb txtMain  = DynoPdfExporter.hexRgb("#14161E");
        com.itextpdf.kernel.colors.DeviceRgb txtMuted = DynoPdfExporter.hexRgb("#666A7A");
        com.itextpdf.kernel.colors.DeviceRgb cellLbl  = DynoPdfExporter.hexRgb("#EEF0F4");
        com.itextpdf.kernel.colors.DeviceRgb cellVal  = DynoPdfExporter.hexRgb("#FFFFFF");
        com.itextpdf.kernel.colors.DeviceRgb border   = DynoPdfExporter.hexRgb("#CDD0D9");
        com.itextpdf.kernel.colors.DeviceRgb altRow   = DynoPdfExporter.hexRgb("#F5F6F9");

        PdfFont font = FontProvider.loadSarabunFont();
        PdfWriter writer = new PdfWriter(outputFile);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document doc = new Document(pdfDoc, PageSize.A4);
        try {
        doc.setMargins(48, 48, 48, 48);
        doc.setFont(font);
        doc.setFontSize(10);

        String title = meta != null && meta.runName != null ? meta.runName : "DYNO RUN EXPORT";
        doc.add(new Paragraph("DYNO RUN DATA EXPORT")
            .setFont(font).setFontSize(17).setBold().setFontColor(txtMain).setMarginBottom(4));
        doc.add(new Paragraph(title)
            .setFont(font).setFontSize(11).setFontColor(accent).setMarginBottom(2));
        if (meta != null && meta.recordedAt != null) {
            doc.add(new Paragraph("Recorded: " + meta.recordedAt)
                .setFont(font).setFontSize(8).setFontColor(txtMuted).setMarginBottom(10));
        }
        doc.add(new Table(UnitValue.createPercentArray(new float[]{1}))
            .setWidth(UnitValue.createPercentValue(100)).setBorder(Border.NO_BORDER).setMarginBottom(12)
            .addCell(new Cell().setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(accent, 2f)).setPadding(0)
                .add(new Paragraph("").setFontSize(1))));

        if (meta != null) {
            doc.add(new Paragraph("PEAK VALUES")
                .setFont(font).setFontSize(10).setBold().setFontColor(txtMuted)
                .setMarginTop(8).setMarginBottom(4));
            Table peakTable = new Table(UnitValue.createPercentArray(new float[]{1.6f, 2.4f}))
                .setWidth(UnitValue.createPercentValue(100));
            addRow(peakTable, "Peak Power", fmtVal(meta.peakHp, "HP"), font, txtMain, cellLbl, cellVal, border);
            addRow(peakTable, "Peak Torque", fmtVal(meta.peakTorqueNm, "Nm"), font, txtMain, cellLbl, cellVal, border);
            if (meta.calibrationId != null && !meta.calibrationId.isEmpty()) {
                addRow(peakTable, "Calibration", meta.calibrationId, font, txtMain, cellLbl, cellVal, border);
            }
            doc.add(peakTable);
            doc.add(new Paragraph("").setMarginTop(8).setFontSize(1));
        }

        doc.add(new Paragraph("FRAME DATA  (RPM step: " + effectiveStep + ")")
            .setFont(font).setFontSize(10).setBold().setFontColor(txtMuted)
            .setMarginTop(8).setMarginBottom(4));

        Table dataTable = new Table(UnitValue.createPercentArray(new float[]{1f, 1f, 1f, 1f, 1f, 1f}))
            .setWidth(UnitValue.createPercentValue(100));
        String[] hdrs = {"RPM", "Power (HP)", "Torque (Nm)", "Speed (km/h)", "AFR", "Lambda"};
        for (String h : hdrs) {
            dataTable.addHeaderCell(new Cell()
                .add(new Paragraph(h).setFont(font).setFontSize(8).setBold().setFontColor(txtMain))
                .setBackgroundColor(cellLbl).setBorder(new SolidBorder(border, 0.5f)).setPadding(4));
        }
        boolean alt = false;
        for (RunHistoryFrameDto f : buckets.values()) {
            com.itextpdf.kernel.colors.DeviceRgb bg = alt ? altRow : cellVal;
            addDataCell(dataTable, fmt(f.getEngineRpm()), font, txtMain, bg, border);
            addDataCell(dataTable, fmt(f.getPowerHp()),   font, txtMain, bg, border);
            addDataCell(dataTable, fmt(f.getTorqueNm()),  font, txtMain, bg, border);
            addDataCell(dataTable, fmt(f.getSpeedKmh()),  font, txtMain, bg, border);
            addDataCell(dataTable, fmt(f.getAfr()),       font, txtMain, bg, border);
            addDataCell(dataTable, fmt(f.getLambda()),    font, txtMain, bg, border);
            alt = !alt;
        }
        doc.add(dataTable);

        doc.add(new Table(UnitValue.createPercentArray(new float[]{1}))
            .setWidth(UnitValue.createPercentValue(100)).setBorder(Border.NO_BORDER).setMarginTop(8)
            .addCell(new Cell().setBorder(Border.NO_BORDER)
                .setBorderTop(new SolidBorder(border, 0.5f)).setPadding(4)
                .add(new Paragraph("Generated by Dyno Operator Console")
                    .setFont(font).setFontSize(8).setFontColor(txtMuted)
                    .setTextAlignment(TextAlignment.CENTER))));

        } finally {
            doc.close();
        }
    }

    static TreeMap<Integer, RunHistoryFrameDto> bucket(List<RunHistoryFrameDto> frames, int rpmStep) {
        TreeMap<Integer, RunHistoryFrameDto> map = new TreeMap<>();
        for (int i = 0; i < frames.size(); i++) {
            RunHistoryFrameDto f = frames.get(i);
            if (f.getEngineRpm() == null || f.getEngineRpm().doubleValue() <= 0) continue;
            double rpm = f.getEngineRpm().doubleValue();
            int key = (int) Math.round(rpm / rpmStep);
            if (!map.containsKey(key)) {
                map.put(key, f);
            } else {
                double center = key * (double) rpmStep;
                double existDist = Math.abs(map.get(key).getEngineRpm().doubleValue() - center);
                if (Math.abs(rpm - center) < existDist) {
                    map.put(key, f);
                }
            }
        }
        return map;
    }

    private static void addRow(Table t, String label, String value, PdfFont font,
                                com.itextpdf.kernel.colors.DeviceRgb txtMain,
                                com.itextpdf.kernel.colors.DeviceRgb cellLbl,
                                com.itextpdf.kernel.colors.DeviceRgb cellVal,
                                com.itextpdf.kernel.colors.DeviceRgb border) {
        t.addCell(new Cell()
            .add(new Paragraph(label).setFont(font).setFontSize(9).setFontColor(txtMain))
            .setBackgroundColor(cellLbl).setBorder(new SolidBorder(border, 0.5f)).setPadding(5));
        t.addCell(new Cell()
            .add(new Paragraph(value).setFont(font).setFontSize(9).setFontColor(txtMain))
            .setBackgroundColor(cellVal).setBorder(new SolidBorder(border, 0.5f)).setPadding(5));
    }

    private static void addDataCell(Table t, String value, PdfFont font,
                                     com.itextpdf.kernel.colors.DeviceRgb txtMain,
                                     com.itextpdf.kernel.colors.DeviceRgb bg,
                                     com.itextpdf.kernel.colors.DeviceRgb border) {
        t.addCell(new Cell()
            .add(new Paragraph(value).setFont(font).setFontSize(8).setFontColor(txtMain))
            .setBackgroundColor(bg).setBorder(new SolidBorder(border, 0.5f)).setPadding(3));
    }

    private static String fmt(Double v) {
        return v == null ? "" : FMT_1D.format(v.doubleValue());
    }

    private static String fmtVal(Double v, String unit) {
        return v == null ? "—" : FMT_1D.format(v.doubleValue()) + " " + unit;
    }
}
