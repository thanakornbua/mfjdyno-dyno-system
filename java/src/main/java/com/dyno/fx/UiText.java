package com.dyno.fx;

import java.util.LinkedHashMap;
import java.util.Map;

final class UiText {
    enum Language {
        ENGLISH,
        THAI
    }

    private static final Map<String, String> EXACT_THAI = new LinkedHashMap<String, String>();
    private static final Map<String, String> REPLACE_THAI = new LinkedHashMap<String, String>();

    private static Language currentLanguage = Language.ENGLISH;

    static {
        EXACT_THAI.put("Dyno Operator Console", "คอนโซลควบคุมไดโน");
        EXACT_THAI.put("Toolbar and operator status", "แถบเครื่องมือและสถานะผู้ควบคุม");
        EXACT_THAI.put("SYSTEM STATUS", "สถานะระบบ");
        EXACT_THAI.put("RUN MODE", "โหมดรัน");
        EXACT_THAI.put("START", "เริ่ม");
        EXACT_THAI.put("STOP", "หยุด");
        EXACT_THAI.put("PRINT", "พิมพ์");
        EXACT_THAI.put("COMPARE", "เทียบ");
        EXACT_THAI.put("CALIBRATION", "คาลิเบรชัน");
        EXACT_THAI.put("CALIBRATION PROFILES", "โปรไฟล์คาลิเบรชัน");
        EXACT_THAI.put("ACTIVE PROFILE", "โปรไฟล์ที่ใช้งานอยู่");
        EXACT_THAI.put("SELECTED PROFILE", "โปรไฟล์ที่เลือก");
        EXACT_THAI.put("PROFILE FORM", "แบบฟอร์มโปรไฟล์");
        EXACT_THAI.put("AUDIT HISTORY", "ประวัติการเปลี่ยนแปลง");
        EXACT_THAI.put("ACTIVATE", "เปิดใช้งาน");
        EXACT_THAI.put("CREATE NEW", "สร้างใหม่");
        EXACT_THAI.put("CREATE PROFILE", "สร้างโปรไฟล์");
        EXACT_THAI.put("SAVE CHANGES", "บันทึกการแก้ไข");
        EXACT_THAI.put("DUPLICATE", "ทำสำเนา");
        EXACT_THAI.put("Activate after save", "เปิดใช้งานหลังบันทึก");
        EXACT_THAI.put("ACTIVE", "ใช้งานอยู่");
        EXACT_THAI.put("INACTIVE", "ไม่ได้ใช้งาน");
        EXACT_THAI.put("STATUS", "สถานะ");
        EXACT_THAI.put("PROFILE", "โปรไฟล์");
        EXACT_THAI.put("TIME", "เวลา");
        EXACT_THAI.put("EVENT", "เหตุการณ์");
        EXACT_THAI.put("SUMMARY", "สรุป");
        EXACT_THAI.put("ROLLER DIAMETER", "เส้นผ่านศูนย์กลางลูกกลิ้ง");
        EXACT_THAI.put("PULSES / REV", "พัลส์ / รอบ");
        EXACT_THAI.put("INERTIA", "โมเมนต์ความเฉื่อย");
        EXACT_THAI.put("SAMPLE WINDOW", "ช่วงเวลาตัวอย่าง");
        EXACT_THAI.put("Warnings", "คำเตือน");
        EXACT_THAI.put("Errors", "ข้อผิดพลาด");
        EXACT_THAI.put("Validation: valid", "การตรวจสอบ: ใช้งานได้");
        EXACT_THAI.put("Validation: warnings", "การตรวจสอบ: มีคำเตือน");
        EXACT_THAI.put("Validation: invalid", "การตรวจสอบ: ไม่ถูกต้อง");
        EXACT_THAI.put("none", "ไม่มี");
        EXACT_THAI.put("No calibration profiles found.", "ไม่พบโปรไฟล์คาลิเบรชัน");
        EXACT_THAI.put("Inspect stored calibration profiles and activate one for future/live calculations.", "ตรวจสอบโปรไฟล์คาลิเบรชันที่บันทึกไว้และเปิดใช้งานสำหรับการคำนวณสด/ในอนาคต");
        EXACT_THAI.put("Inspect stored calibration profiles and manage create/edit/duplicate/activate actions.", "ตรวจสอบโปรไฟล์คาลิเบรชันและจัดการการสร้าง แก้ไข ทำสำเนา และเปิดใช้งาน");
        EXACT_THAI.put("Activation applies to future/live calculations immediately. Stored runs keep their original calibration snapshot.", "การเปิดใช้งานมีผลกับการคำนวณสด/ในอนาคตทันที ส่วนรันที่บันทึกแล้วจะคงค่าสแนปชอตเดิมไว้");
        EXACT_THAI.put("Select a profile to inspect its validation state.", "เลือกโปรไฟล์เพื่อตรวจสอบสถานะการตรวจสอบ");
        EXACT_THAI.put("Select a profile to inspect, edit, duplicate, or activate it.", "เลือกโปรไฟล์เพื่อตรวจสอบ แก้ไข ทำสำเนา หรือเปิดใช้งาน");
        EXACT_THAI.put("Select a calibration profile first.", "เลือกโปรไฟล์คาลิเบรชันก่อน");
        EXACT_THAI.put("Selected profile is already active.", "โปรไฟล์ที่เลือกถูกใช้งานอยู่แล้ว");
        EXACT_THAI.put("Selected profile cannot be activated until validation errors are resolved.", "โปรไฟล์ที่เลือกไม่สามารถเปิดใช้งานได้จนกว่าจะแก้ข้อผิดพลาดการตรวจสอบ");
        EXACT_THAI.put("Activated calibration: ", "เปิดใช้งานคาลิเบรชัน: ");
        EXACT_THAI.put("Activated calibration with warnings: ", "เปิดใช้งานคาลิเบรชันพร้อมคำเตือน: ");
        EXACT_THAI.put("Calibration activation interrupted.", "การเปิดใช้งานคาลิเบรชันถูกยกเลิก");
        EXACT_THAI.put("Calibration activation failed: ", "การเปิดใช้งานคาลิเบรชันล้มเหลว: ");
        EXACT_THAI.put("Loading calibration profiles...", "กำลังโหลดโปรไฟล์คาลิเบรชัน...");
        EXACT_THAI.put("Calibration request interrupted.", "คำขอคาลิเบรชันถูกยกเลิก");
        EXACT_THAI.put("Failed to load audit history: ", "โหลดประวัติการเปลี่ยนแปลงไม่สำเร็จ: ");
        EXACT_THAI.put("Select a profile to load audit history.", "เลือกโปรไฟล์เพื่อโหลดประวัติการเปลี่ยนแปลง");
        EXACT_THAI.put("Creating a new calibration profile.", "กำลังสร้างโปรไฟล์คาลิเบรชันใหม่");
        EXACT_THAI.put("Saving calibration profile...", "กำลังบันทึกโปรไฟล์คาลิเบรชัน...");
        EXACT_THAI.put("Duplicating calibration profile...", "กำลังทำสำเนาโปรไฟล์คาลิเบรชัน...");
        EXACT_THAI.put("Activating calibration profile...", "กำลังเปิดใช้งานโปรไฟล์คาลิเบรชัน...");
        EXACT_THAI.put("Saved calibration profile: ", "บันทึกโปรไฟล์คาลิเบรชัน: ");
        EXACT_THAI.put("Duplicated calibration profile: ", "ทำสำเนาโปรไฟล์คาลิเบรชัน: ");
        EXACT_THAI.put("Resolve validation errors before saving.", "แก้ข้อผิดพลาดการตรวจสอบก่อนบันทึก");
        EXACT_THAI.put("Create calibration profile", "สร้างโปรไฟล์คาลิเบรชัน");
        EXACT_THAI.put("Edit calibration profile", "แก้ไขโปรไฟล์คาลิเบรชัน");
        EXACT_THAI.put("A new profile will be created. It will only become active if 'Activate after save' is checked.", "ระบบจะสร้างโปรไฟล์ใหม่ และจะเปิดใช้งานก็ต่อเมื่อเลือก 'เปิดใช้งานหลังบันทึก'");
        EXACT_THAI.put("Select a profile to edit or choose create new.", "เลือกโปรไฟล์เพื่อแก้ไขหรือเลือกสร้างใหม่");
        EXACT_THAI.put("No profile data available.", "ไม่มีข้อมูลโปรไฟล์");
        EXACT_THAI.put("Validation unavailable.", "ไม่มีข้อมูลการตรวจสอบ");
        EXACT_THAI.put("No profile selected.", "ยังไม่ได้เลือกโปรไฟล์");
        EXACT_THAI.put("Profile name", "ชื่อโปรไฟล์");
        EXACT_THAI.put("Roller diameter (m)", "เส้นผ่านศูนย์กลางลูกกลิ้ง (ม.)");
        EXACT_THAI.put("Pulses / rev", "พัลส์ / รอบ");
        EXACT_THAI.put("Inertia (kg·m²)", "โมเมนต์ความเฉื่อย (กก.·ม.^2)");
        EXACT_THAI.put("Sample window (ms)", "ช่วงเวลาตัวอย่าง (มิลลิวินาที)");
        EXACT_THAI.put("Engine pulses hint", "ค่าชี้แนะพัลส์เครื่องยนต์");
        EXACT_THAI.put("Engine RPM scale", "สเกลรอบเครื่องยนต์");
        EXACT_THAI.put("Notes", "หมายเหตุ");
        EXACT_THAI.put("LIVE DYNO CHART", "กราฟไดโนสด");
        EXACT_THAI.put("Configured axes will appear here.", "แกนที่ตั้งค่าไว้จะแสดงที่นี่");
        EXACT_THAI.put("Configure and start a run to draw the dyno chart.", "ตั้งค่ารอบทดสอบและเริ่มรันเพื่อวาดกราฟไดโน");
        EXACT_THAI.put("PEAK VALUES", "ค่าสูงสุด");
        EXACT_THAI.put("SELECTED AXES", "แกนที่เลือก");
        EXACT_THAI.put("COMPARE / PRINT", "เทียบ / พิมพ์");
        EXACT_THAI.put("CURRENT RUN", "รันปัจจุบัน");
        EXACT_THAI.put("COMPARE SUMMARY", "สรุปการเทียบ");
        EXACT_THAI.put("No comparison loaded", "ยังไม่ได้โหลดข้อมูลเทียบ");
        EXACT_THAI.put("OPERATOR STATUS", "สถานะผู้ควบคุม");
        EXACT_THAI.put("CONTINUOUS TELEMETRY", "ค่าถ่ายทอดสด");
        EXACT_THAI.put("ENGINE", "เครื่องยนต์");
        EXACT_THAI.put("FUEL / ENV", "เชื้อเพลิง / สภาพแวดล้อม");
        EXACT_THAI.put("CHART CONTEXT", "ข้อมูลกราฟ");
        EXACT_THAI.put("RUN NOT CONFIGURED", "ยังไม่ได้ตั้งค่ารัน");
        EXACT_THAI.put("RUN READY", "พร้อมเริ่มรัน");
        EXACT_THAI.put("RUN ACTIVE", "กำลังรัน");
        EXACT_THAI.put("RUN STARTED", "เริ่มรันแล้ว");
        EXACT_THAI.put("LIVE TELEMETRY", "ค่าถ่ายทอดสด");
        EXACT_THAI.put("Live telemetry active (not saved)", "ค่าถ่ายทอดสดกำลังทำงาน (ยังไม่บันทึก)");
        EXACT_THAI.put("Enter license plate to configure a run", "กรอกป้ายทะเบียนเพื่อตั้งค่ารัน");
        EXACT_THAI.put("Enter license plate to configure a run.", "กรอกป้ายทะเบียนเพื่อตั้งค่ารัน");
        EXACT_THAI.put("Ready to start run", "พร้อมเริ่มรัน");
        EXACT_THAI.put("RECORDING ACTIVE", "กำลังบันทึก");
        EXACT_THAI.put("ARMED", "เตรียมพร้อม");
        EXACT_THAI.put("Waiting for run thresholds", "กำลังรอค่ากำหนดเริ่มรัน");
        EXACT_THAI.put("Run started — waiting for movement", "เริ่มรันแล้ว — กำลังรอการเคลื่อนที่");
        EXACT_THAI.put("Compare selection and print/export remain future chart-adjacent hooks.", "การเทียบและพิมพ์/ส่งออกจะเพิ่มในส่วนกราฟภายหลัง");
        EXACT_THAI.put("Previous-run comparison and timestamps will land here in a later step.", "ข้อมูลเทียบรอบก่อนและเวลา จะแสดงที่นี่ในขั้นถัดไป");
        EXACT_THAI.put("Run/chart configuration stays tied to run setup and chart context.", "การตั้งค่ารัน/กราฟยังคงผูกกับการตั้งค่ารันและบริบทกราฟ");
        EXACT_THAI.put("DISCONNECTED", "ตัดการเชื่อมต่อ");
        EXACT_THAI.put("Disconnected from dyno backend", "ไม่ได้เชื่อมต่อกับระบบไดโน");
        EXACT_THAI.put("CONNECTED", "เชื่อมต่อแล้ว");
        EXACT_THAI.put("RECONNECTING", "กำลังเชื่อมต่อใหม่");
        EXACT_THAI.put("CONNECTING", "กำลังเชื่อมต่อ");
        EXACT_THAI.put("READY", "พร้อมใช้งาน");
        EXACT_THAI.put("DEGRADED", "มีข้อจำกัด");
        EXACT_THAI.put("UNAVAILABLE", "ไม่พร้อมใช้งาน");
        EXACT_THAI.put("BACKEND UNAVAILABLE", "แบ็กเอนด์ไม่พร้อมใช้งาน");
        EXACT_THAI.put("BACKEND DEGRADED", "แบ็กเอนด์มีข้อจำกัด");
        EXACT_THAI.put("Backend ready", "แบ็กเอนด์พร้อมใช้งาน");
        EXACT_THAI.put("Backend degraded", "แบ็กเอนด์มีข้อจำกัด");
        EXACT_THAI.put("Backend unavailable", "แบ็กเอนด์ไม่พร้อมใช้งาน");
        EXACT_THAI.put("Checking backend status...", "กำลังตรวจสอบสถานะแบ็กเอนด์...");
        EXACT_THAI.put("Automatic status refresh active", "กำลังรีเฟรชสถานะอัตโนมัติ");
        EXACT_THAI.put("Status checks retry automatically", "ระบบจะลองตรวจสอบสถานะอีกครั้งอัตโนมัติ");
        EXACT_THAI.put("Health checks passing", "การตรวจสอบระบบผ่านทั้งหมด");
        EXACT_THAI.put("Live mode active", "โหมดสดกำลังทำงาน");
        EXACT_THAI.put("Replay mode active", "โหมดรีเพลย์กำลังทำงาน");
        EXACT_THAI.put("Storage unavailable", "ระบบจัดเก็บข้อมูลไม่พร้อมใช้งาน");
        EXACT_THAI.put("Serial input unavailable — retrying", "อินพุตอนุกรมไม่พร้อมใช้งาน — กำลังลองใหม่");
        EXACT_THAI.put("Ambient sensor unavailable — fallback values in use", "เซ็นเซอร์สภาพแวดล้อมไม่พร้อมใช้งาน — ใช้ค่าทดแทนอยู่");
        EXACT_THAI.put("IDLE", "ว่าง");
        EXACT_THAI.put("RECORDING", "กำลังบันทึก");
        EXACT_THAI.put("FAULT", "ขัดข้อง");
        EXACT_THAI.put("Sending STOP request...", "กำลังส่งคำสั่งหยุด...");
        EXACT_THAI.put("Configuring run...", "กำลังตั้งค่ารัน...");
        EXACT_THAI.put("Starting run...", "กำลังเริ่มรัน...");
        EXACT_THAI.put("Print/export will be wired to persisted run data in a later step.", "การพิมพ์/ส่งออกจะเชื่อมกับข้อมูลรันที่บันทึกไว้ในขั้นถัดไป");
        EXACT_THAI.put("Previous-run comparison will be added through a popup selector in a later step.", "การเทียบรอบก่อนจะเพิ่มผ่านตัวเลือกป๊อปอัปในขั้นถัดไป");
        EXACT_THAI.put("Run control request interrupted.", "คำขอควบคุมรันถูกยกเลิก");
        EXACT_THAI.put("Gauge", "เกจ");
        EXACT_THAI.put("Metric", "ค่า");

        // Export / print pipeline
        EXACT_THAI.put("Export Run Data", "ส่งออกข้อมูลรัน");
        EXACT_THAI.put("Export formats:", "รูปแบบการส่งออก:");
        EXACT_THAI.put("Output folder:", "โฟลเดอร์ปลายทาง:");
        EXACT_THAI.put("Browse...", "เลือก...");
        EXACT_THAI.put("Select Output Folder", "เลือกโฟลเดอร์ปลายทาง");
        EXACT_THAI.put("EXPORT", "ส่งออก");
        EXACT_THAI.put("PDF Report", "รายงาน PDF");
        EXACT_THAI.put("Chart Image (PNG)", "ภาพกราฟ (PNG)");
        EXACT_THAI.put("Frame Data (CSV)", "ข้อมูลเฟรม (CSV)");
        EXACT_THAI.put("Run Data (JSON)", "ข้อมูลรัน (JSON)");
        EXACT_THAI.put("SELECT RUN TO EXPORT", "เลือกรันเพื่อส่งออก");
        EXACT_THAI.put("Select Run to Export", "เลือกรันเพื่อส่งออก");
        EXACT_THAI.put("SELECT FOR EXPORT", "เลือกเพื่อส่งออก");
        EXACT_THAI.put("Select one stored run to export as PDF, PNG, CSV, or JSON.", "เลือกรันที่บันทึกไว้หนึ่งรันเพื่อส่งออกเป็น PDF, PNG, CSV หรือ JSON");
        EXACT_THAI.put("Loading stored runs for export...", "กำลังโหลดรันที่บันทึกไว้...");
        EXACT_THAI.put("Exporting run data...", "กำลังส่งออกข้อมูลรัน...");
        EXACT_THAI.put("Exporting comparison...", "กำลังส่งออกข้อมูลการเปรียบเทียบ...");
        EXACT_THAI.put("No run data available to export.", "ไม่มีข้อมูลรันสำหรับส่งออก");

        REPLACE_THAI.put("Peak power: ", "กำลังสูงสุด: ");
        REPLACE_THAI.put("Name: ", "ชื่อ: ");
        REPLACE_THAI.put("Editing profile: ", "กำลังแก้ไขโปรไฟล์: ");
        REPLACE_THAI.put("Roller diameter: ", "เส้นผ่านศูนย์กลางลูกกลิ้ง: ");
        REPLACE_THAI.put("Pulses/rev: ", "พัลส์/รอบ: ");
        REPLACE_THAI.put("Inertia: ", "โมเมนต์ความเฉื่อย: ");
        REPLACE_THAI.put("Sample window: ", "ช่วงเวลาตัวอย่าง: ");
        REPLACE_THAI.put(". Future calculations now use this profile.", ". การคำนวณในอนาคตจะใช้โปรไฟล์นี้");
        REPLACE_THAI.put("Peak torque: ", "แรงบิดสูงสุด: ");
        REPLACE_THAI.put("Plate: ", "ป้ายทะเบียน: ");
        REPLACE_THAI.put("Connection: ", "การเชื่อมต่อ: ");
        REPLACE_THAI.put("State: ", "สถานะ: ");
        REPLACE_THAI.put(" startup warning active", " คำเตือนการเริ่มระบบทำงานอยู่");
        REPLACE_THAI.put(" startup warnings active", " คำเตือนการเริ่มระบบทำงานอยู่");
        REPLACE_THAI.put("Recording active — ", "กำลังบันทึก — ");
        REPLACE_THAI.put("Previous-run comparison and timestamps will attach beneath ", "ข้อมูลเทียบรอบก่อนและเวลาจะอยู่ใต้ ");
        REPLACE_THAI.put("Run control request failed: ", "คำขอควบคุมรันล้มเหลว: ");
        REPLACE_THAI.put("Engine RPM", "รอบเครื่องยนต์");
        REPLACE_THAI.put("Power", "กำลัง");
        REPLACE_THAI.put("Torque", "แรงบิด");
        REPLACE_THAI.put("Speed", "ความเร็ว");
        REPLACE_THAI.put("Pressure", "ความดัน");
        REPLACE_THAI.put("Humidity", "ความชื้น");
        REPLACE_THAI.put("Temp", "อุณหภูมิ");
        REPLACE_THAI.put("Fault Count", "จำนวนข้อผิดพลาด");
        REPLACE_THAI.put("Configure and start a run to draw the dyno chart.", "ตั้งค่ารอบทดสอบและเริ่มรันเพื่อวาดกราฟไดโน");
    }

    private UiText() {
    }

    static void toggleLanguage() {
        currentLanguage = currentLanguage == Language.ENGLISH ? Language.THAI : Language.ENGLISH;
    }

    static boolean isThai() {
        return currentLanguage == Language.THAI;
    }

    static String languageButtonLabel() {
        return isThai() ? "EN" : "ไทย";
    }

    static String text(String source) {
        if (source == null || !isThai()) {
            return source;
        }
        String exact = EXACT_THAI.get(source);
        if (exact != null) {
            return exact;
        }
        String translated = source;
        for (Map.Entry<String, String> entry : REPLACE_THAI.entrySet()) {
            translated = translated.replace(entry.getKey(), entry.getValue());
        }
        return translated;
    }
}
