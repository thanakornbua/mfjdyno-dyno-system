//! BME280 ambient sensor task (I2C, Pi-side).
//!
//! The task owns a `watch::Sender<AmbientSample>` and updates it at 1 Hz.
//! All Linux I2C access is performed in `spawn_blocking` so the Tokio runtime
//! is never blocked. If `/dev/i2c-1` or the BME280 is unavailable, the task
//! falls back to a stable stub ambient sample instead of crashing.

use std::fs::{File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::AsRawFd;
use std::os::raw::{c_int, c_ulong};
use std::thread;
use std::time::Duration as StdDuration;

use tokio::sync::watch;
use tokio::task::JoinHandle;
use tokio::time::{self, MissedTickBehavior};
use tracing::{debug, info, warn};

const POLL_INTERVAL: StdDuration = StdDuration::from_secs(1);
const I2C_DEVICE_PATH: &str = "/dev/i2c-1";
const BME280_PRIMARY_ADDR: u16 = 0x76;
const BME280_SECONDARY_ADDR: u16 = 0x77;
const I2C_SLAVE: c_ulong = 0x0703;

const REG_CHIP_ID: u8 = 0xD0;
const CHIP_ID_BME280: u8 = 0x60;
const REG_RESET: u8 = 0xE0;
const RESET_CMD: u8 = 0xB6;
const REG_CTRL_HUM: u8 = 0xF2;
const REG_STATUS: u8 = 0xF3;
const REG_CTRL_MEAS: u8 = 0xF4;
const REG_CONFIG: u8 = 0xF5;
const REG_DATA_START: u8 = 0xF7;
const REG_CALIB_1_START: u8 = 0x88;
const REG_CALIB_2_START: u8 = 0xE1;

const STATUS_IM_UPDATE: u8 = 0x01;

/// A single ambient measurement from the BME280.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AmbientSample {
    pub temp_c: f32,
    pub humidity_pct: f32,
    pub pressure_hpa: f32,
}

impl AmbientSample {
    /// Conservative fallback used when hardware is disabled or unavailable.
    pub const fn stub() -> Self {
        Self {
            temp_c: 25.0,
            humidity_pct: 50.0,
            pressure_hpa: 1013.25,
        }
    }

    /// Clamp to the valid/expected frontend range.
    pub fn sanitized(self) -> Self {
        Self {
            temp_c: sanitize_value(self.temp_c, -40.0, 85.0, Self::stub().temp_c),
            humidity_pct: sanitize_value(self.humidity_pct, 0.0, 100.0, Self::stub().humidity_pct),
            pressure_hpa: sanitize_value(self.pressure_hpa, 300.0, 1100.0, Self::stub().pressure_hpa),
        }
    }
}

impl Default for AmbientSample {
    fn default() -> Self {
        Self::stub()
    }
}

/// Handle returned by the BME280 polling task.
pub struct Bme280Task {
    tx: watch::Sender<AmbientSample>,
    handle: JoinHandle<()>,
}

impl Bme280Task {
    /// Spawn the BME280 polling task.
    pub fn spawn(enabled: bool) -> Self {
        let (tx, _rx_guard) = watch::channel(AmbientSample::stub());
        let handle = tokio::spawn(bme280_task_loop(enabled, tx.clone()));

        if enabled {
            info!("bme280 task spawned; polling {I2C_DEVICE_PATH} at 1 Hz");
        } else {
            info!("bme280 disabled by configuration; using stub ambient samples");
        }

        Self { tx, handle }
    }

    /// Subscribe to the latest ambient sample stream.
    pub fn subscribe(&self) -> watch::Receiver<AmbientSample> {
        self.tx.subscribe()
    }
}

impl Drop for Bme280Task {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

async fn bme280_task_loop(enabled: bool, tx: watch::Sender<AmbientSample>) {
    let mut interval = time::interval(POLL_INTERVAL);
    interval.set_missed_tick_behavior(MissedTickBehavior::Skip);
    interval.tick().await;

    if !enabled {
        loop {
            interval.tick().await;
            if tx.send(AmbientSample::stub()).is_err() {
                info!("bme280: all receivers dropped - task stopping");
                return;
            }
        }
    }

    let mut sensor: Option<Bme280Sensor> = None;
    let mut last_source: Option<AmbientSource> = None;

    loop {
        interval.tick().await;

        let current_sensor = sensor.take();
        let result = tokio::task::spawn_blocking(move || poll_hardware_once(current_sensor)).await;

        let outcome = match result {
            Ok(outcome) => outcome,
            Err(err) => {
                warn!("bme280: blocking task join error: {err} - using stub ambient sample");
                AmbientOutcome::stub(AmbientSource::JoinFailure)
            }
        };

        sensor = outcome.sensor;

        if last_source != Some(outcome.source) {
            match outcome.source {
                AmbientSource::Hardware => info!("bme280: hardware reads active"),
                AmbientSource::Unavailable => {
                    warn!("bme280: sensor unavailable on {I2C_DEVICE_PATH} - using stub ambient data")
                }
                AmbientSource::ReadFailed => {
                    warn!("bme280: sensor read failed - using stub ambient data")
                }
                AmbientSource::JoinFailure => {
                    warn!("bme280: background read failed - using stub ambient data")
                }
            }
            last_source = Some(outcome.source);
        }

        if tx.send(outcome.sample).is_err() {
            info!("bme280: all receivers dropped - task stopping");
            return;
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AmbientSource {
    Hardware,
    Unavailable,
    ReadFailed,
    JoinFailure,
}

struct AmbientOutcome {
    sensor: Option<Bme280Sensor>,
    sample: AmbientSample,
    source: AmbientSource,
}

impl AmbientOutcome {
    fn stub(source: AmbientSource) -> Self {
        Self {
            sensor: None,
            sample: AmbientSample::stub(),
            source,
        }
    }
}

fn poll_hardware_once(sensor: Option<Bme280Sensor>) -> AmbientOutcome {
    let mut sensor = match sensor {
        Some(sensor) => sensor,
        None => match Bme280Sensor::open(I2C_DEVICE_PATH) {
            Ok(sensor) => sensor,
            Err(err) => {
                debug!("bme280: open failed: {err}");
                return AmbientOutcome::stub(AmbientSource::Unavailable);
            }
        },
    };

    match sensor.read_sample() {
        Ok(sample) => AmbientOutcome {
            sensor: Some(sensor),
            sample: sample.sanitized(),
            source: AmbientSource::Hardware,
        },
        Err(err) => {
            debug!("bme280: read failed: {err}");
            AmbientOutcome::stub(AmbientSource::ReadFailed)
        }
    }
}

struct Bme280Sensor {
    file: File,
    calibration: Calibration,
    t_fine: i32,
}

impl Bme280Sensor {
    fn open(path: &str) -> io::Result<Self> {
        let mut last_error = None;

        for address in [BME280_PRIMARY_ADDR, BME280_SECONDARY_ADDR] {
            match Self::open_at_address(path, address) {
                Ok(sensor) => return Ok(sensor),
                Err(err) => last_error = Some(err),
            }
        }

        Err(last_error.unwrap_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no BME280 detected")))
    }

    fn open_at_address(path: &str, address: u16) -> io::Result<Self> {
        let mut file = OpenOptions::new().read(true).write(true).open(path)?;
        set_slave_address(&file, address)?;

        let chip_id = read_register(&mut file, REG_CHIP_ID)?;
        if chip_id != CHIP_ID_BME280 {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!("unexpected chip id 0x{chip_id:02x} at address 0x{address:02x}"),
            ));
        }

        write_register(&mut file, REG_RESET, RESET_CMD)?;
        thread::sleep(StdDuration::from_millis(3));

        let mut status = read_register(&mut file, REG_STATUS)?;
        let mut retries = 10;
        while status & STATUS_IM_UPDATE != 0 && retries > 0 {
            thread::sleep(StdDuration::from_millis(2));
            status = read_register(&mut file, REG_STATUS)?;
            retries -= 1;
        }

        let calibration = Calibration::read_from(&mut file)?;

        // Humidity oversampling x1 must be written before ctrl_meas.
        write_register(&mut file, REG_CTRL_HUM, 0x01)?;
        // Temp x1, pressure x1, normal mode.
        write_register(&mut file, REG_CTRL_MEAS, 0x27)?;
        // Standby 1000 ms, filter off.
        write_register(&mut file, REG_CONFIG, 0xA0)?;

        Ok(Self {
            file,
            calibration,
            t_fine: 0,
        })
    }

    fn read_sample(&mut self) -> io::Result<AmbientSample> {
        let mut buf = [0u8; 8];
        read_registers(&mut self.file, REG_DATA_START, &mut buf)?;

        let adc_pressure =
            ((i32::from(buf[0])) << 12) | ((i32::from(buf[1])) << 4) | (i32::from(buf[2]) >> 4);
        let adc_temp =
            ((i32::from(buf[3])) << 12) | ((i32::from(buf[4])) << 4) | (i32::from(buf[5]) >> 4);
        let adc_humidity = (i32::from(buf[6]) << 8) | i32::from(buf[7]);

        let temp_c = self.compensate_temperature(adc_temp);
        let pressure_hpa = self.compensate_pressure_hpa(adc_pressure)?;
        let humidity_pct = self.compensate_humidity_pct(adc_humidity);

        Ok(AmbientSample {
            temp_c,
            humidity_pct,
            pressure_hpa,
        })
    }

    fn compensate_temperature(&mut self, adc_temp: i32) -> f32 {
        let var1 = (((adc_temp >> 3) - (i32::from(self.calibration.dig_t1) << 1))
            * i32::from(self.calibration.dig_t2))
            >> 11;

        let var2 = (((((adc_temp >> 4) - i32::from(self.calibration.dig_t1))
            * ((adc_temp >> 4) - i32::from(self.calibration.dig_t1)))
            >> 12)
            * i32::from(self.calibration.dig_t3))
            >> 14;

        self.t_fine = var1 + var2;
        ((self.t_fine * 5 + 128) >> 8) as f32 / 100.0
    }

    fn compensate_pressure_hpa(&self, adc_pressure: i32) -> io::Result<f32> {
        let mut var1 = i64::from(self.t_fine) - 128_000;
        let mut var2 = var1 * var1 * i64::from(self.calibration.dig_p6);
        var2 += (var1 * i64::from(self.calibration.dig_p5)) << 17;
        var2 += i64::from(self.calibration.dig_p4) << 35;
        var1 = ((var1 * var1 * i64::from(self.calibration.dig_p3)) >> 8)
            + ((var1 * i64::from(self.calibration.dig_p2)) << 12);
        var1 = (((1_i64 << 47) + var1) * i64::from(self.calibration.dig_p1)) >> 33;

        if var1 == 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid pressure calibration"));
        }

        let mut pressure = 1_048_576_i64 - i64::from(adc_pressure);
        pressure = (((pressure << 31) - var2) * 3_125) / var1;
        var1 = (i64::from(self.calibration.dig_p9) * (pressure >> 13) * (pressure >> 13)) >> 25;
        var2 = (i64::from(self.calibration.dig_p8) * pressure) >> 19;
        pressure = ((pressure + var1 + var2) >> 8) + (i64::from(self.calibration.dig_p7) << 4);

        Ok((pressure as f32 / 256.0) / 100.0)
    }

    fn compensate_humidity_pct(&self, adc_humidity: i32) -> f32 {
        let mut humidity = self.t_fine as f32 - 76_800.0;

        humidity = (adc_humidity as f32
            - (self.calibration.dig_h4 as f32 * 64.0
                + self.calibration.dig_h5 as f32 / 16_384.0 * humidity))
            * (self.calibration.dig_h2 as f32 / 65_536.0
                * (1.0
                    + self.calibration.dig_h6 as f32 / 67_108_864.0 * humidity
                        * (1.0 + self.calibration.dig_h3 as f32 / 67_108_864.0 * humidity)));

        humidity *= 1.0 - self.calibration.dig_h1 as f32 * humidity / 524_288.0;
        humidity.clamp(0.0, 100.0)
    }
}

#[derive(Debug, Clone, Copy)]
struct Calibration {
    dig_t1: u16,
    dig_t2: i16,
    dig_t3: i16,
    dig_p1: u16,
    dig_p2: i16,
    dig_p3: i16,
    dig_p4: i16,
    dig_p5: i16,
    dig_p6: i16,
    dig_p7: i16,
    dig_p8: i16,
    dig_p9: i16,
    dig_h1: u8,
    dig_h2: i16,
    dig_h3: u8,
    dig_h4: i16,
    dig_h5: i16,
    dig_h6: i8,
}

impl Calibration {
    fn read_from(file: &mut File) -> io::Result<Self> {
        let mut calib1 = [0u8; 26];
        read_registers(file, REG_CALIB_1_START, &mut calib1)?;

        let mut calib2 = [0u8; 7];
        read_registers(file, REG_CALIB_2_START, &mut calib2)?;

        Ok(Self {
            dig_t1: le_u16(&calib1[0..2]),
            dig_t2: le_i16(&calib1[2..4]),
            dig_t3: le_i16(&calib1[4..6]),
            dig_p1: le_u16(&calib1[6..8]),
            dig_p2: le_i16(&calib1[8..10]),
            dig_p3: le_i16(&calib1[10..12]),
            dig_p4: le_i16(&calib1[12..14]),
            dig_p5: le_i16(&calib1[14..16]),
            dig_p6: le_i16(&calib1[16..18]),
            dig_p7: le_i16(&calib1[18..20]),
            dig_p8: le_i16(&calib1[20..22]),
            dig_p9: le_i16(&calib1[22..24]),
            dig_h1: calib1[25],
            dig_h2: le_i16(&calib2[0..2]),
            dig_h3: calib2[2],
            dig_h4: signed_12bit((i16::from(calib2[3]) << 4) | i16::from(calib2[4] & 0x0F)),
            dig_h5: signed_12bit((i16::from(calib2[5]) << 4) | i16::from(calib2[4] >> 4)),
            dig_h6: calib2[6] as i8,
        })
    }
}

fn sanitize_value(value: f32, min: f32, max: f32, fallback: f32) -> f32 {
    if !value.is_finite() {
        fallback
    } else {
        value.clamp(min, max)
    }
}

fn le_u16(bytes: &[u8]) -> u16 {
    u16::from_le_bytes([bytes[0], bytes[1]])
}

fn le_i16(bytes: &[u8]) -> i16 {
    i16::from_le_bytes([bytes[0], bytes[1]])
}

fn signed_12bit(value: i16) -> i16 {
    if value & 0x0800 != 0 {
        value | !0x0FFF
    } else {
        value
    }
}

fn set_slave_address(file: &File, address: u16) -> io::Result<()> {
    let fd = file.as_raw_fd();
    let rc = unsafe { ioctl(fd, I2C_SLAVE, c_int::from(address)) };
    if rc == -1 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn write_register(file: &mut File, register: u8, value: u8) -> io::Result<()> {
    file.write_all(&[register, value])
}

fn read_register(file: &mut File, register: u8) -> io::Result<u8> {
    let mut buf = [0u8; 1];
    read_registers(file, register, &mut buf)?;
    Ok(buf[0])
}

fn read_registers(file: &mut File, register: u8, buf: &mut [u8]) -> io::Result<()> {
    file.write_all(&[register])?;
    file.read_exact(buf)
}

extern "C" {
    fn ioctl(fd: c_int, request: c_ulong, ...) -> c_int;
}
