use std::ffi::CString;
use std::io;
use std::mem;
use std::os::fd::RawFd;
use std::time::Duration;

use tokio::sync::watch;
use tokio::task::JoinHandle;
use tracing::{debug, info, warn};

const RECONNECT_DELAY: Duration = Duration::from_secs(5);
const READ_TIMEOUT: Duration = Duration::from_millis(500);
const AEM_ID_START: u32 = 0x180;
const AEM_ID_END: u32 = 0x18F;
const LAMBDA_FREE_AIR_RAW: u16 = 0xFFFF;
const STOICH_AFR: f32 = 14.65;
const CAN_EFF_FLAG: u32 = 0x8000_0000;
const CAN_EFF_MASK: u32 = 0x1FFF_FFFF;

#[derive(Debug, Clone, PartialEq)]
pub struct CanSample {
    pub can_present: bool,
    pub can_frames_seen: u64,
    pub afr_valid: bool,
    pub can_valid: bool,
    pub afr: Option<f32>,
    pub lambda: Option<f32>,
    pub oxygen: Option<f32>,
    pub volts: Option<f32>,
    pub free_air: bool,
    pub lambda_valid_flag: bool,
    pub sensor_fault: bool,
    pub status_text: String,
}

impl CanSample {
    pub fn missing() -> Self {
        Self {
            can_present: false,
            can_frames_seen: 0,
            afr_valid: false,
            can_valid: false,
            afr: None,
            lambda: None,
            oxygen: None,
            volts: None,
            free_air: false,
            lambda_valid_flag: false,
            sensor_fault: false,
            status_text: "CAN missing".to_owned(),
        }
    }

    fn present_no_frames(frames_seen: u64) -> Self {
        Self {
            can_present: true,
            can_frames_seen: frames_seen,
            afr_valid: false,
            can_valid: true,
            afr: None,
            lambda: None,
            oxygen: None,
            volts: None,
            free_air: false,
            lambda_valid_flag: false,
            sensor_fault: false,
            status_text: if frames_seen == 0 {
                "CAN present, waiting for AEM UEGO".to_owned()
            } else {
                "CAN present, no recent AEM UEGO".to_owned()
            },
        }
    }
}

impl Default for CanSample {
    fn default() -> Self {
        Self::missing()
    }
}

pub struct CanTask {
    handle: JoinHandle<()>,
}

impl CanTask {
    pub fn spawn(iface: String, tx: watch::Sender<CanSample>) -> Self {
        let handle = tokio::spawn(can_task_loop(iface, tx));
        info!("SocketCAN task spawned");
        Self { handle }
    }
}

impl Drop for CanTask {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

async fn can_task_loop(iface: String, tx: watch::Sender<CanSample>) {
    loop {
        let socket = match CanSocket::open(&iface) {
            Ok(socket) => {
                info!("can: opened SocketCAN interface {iface}");
                socket
            }
            Err(err) => {
                warn!("can: interface {iface} unavailable: {err}; retrying in {RECONNECT_DELAY:?}");
                if tx.send(CanSample::missing()).is_err() {
                    info!("can: all receivers dropped - task stopping");
                    return;
                }
                tokio::time::sleep(RECONNECT_DELAY).await;
                continue;
            }
        };

        let mut frames_seen = 0u64;
        if tx.send(CanSample::present_no_frames(frames_seen)).is_err() {
            info!("can: all receivers dropped - task stopping");
            return;
        }

        let mut socket = socket;
        loop {
            let result = tokio::task::spawn_blocking(move || {
                let read_result = socket.read_frame();
                (socket, read_result)
            })
            .await;

            let (returned_socket, read_result) = match result {
                Ok(result) => result,
                Err(err) => {
                    warn!("can: blocking read task failed: {err}; reopening in {RECONNECT_DELAY:?}");
                    tokio::time::sleep(RECONNECT_DELAY).await;
                    break;
                }
            };
            socket = returned_socket;

            match read_result {
                Ok(Some(frame)) => {
                    if let Some(mut sample) = decode_aem_uego(frame.can_id, &frame.data[..frame.len]) {
                        frames_seen = frames_seen.saturating_add(1);
                        sample.can_frames_seen = frames_seen;
                        if tx.send(sample).is_err() {
                            info!("can: all receivers dropped - task stopping");
                            return;
                        }
                    }
                }
                Ok(None) => {
                    let sample = CanSample::present_no_frames(frames_seen);
                    if tx.send(sample).is_err() {
                        info!("can: all receivers dropped - task stopping");
                        return;
                    }
                }
                Err(err) => {
                    warn!("can: read failed on {iface}: {err}; reopening in {RECONNECT_DELAY:?}");
                    tokio::time::sleep(RECONNECT_DELAY).await;
                    break;
                }
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
struct CanFrame {
    can_id: u32,
    len: usize,
    data: [u8; 8],
}

struct CanSocket {
    fd: RawFd,
}

impl CanSocket {
    fn open(iface: &str) -> io::Result<Self> {
        let iface_c = CString::new(iface)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "CAN interface contains NUL"))?;
        let ifindex = unsafe { libc::if_nametoindex(iface_c.as_ptr()) };
        if ifindex == 0 {
            return Err(io::Error::last_os_error());
        }

        let fd = unsafe { libc::socket(libc::PF_CAN, libc::SOCK_RAW, libc::CAN_RAW) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }

        let socket = Self { fd };
        if let Err(err) = socket.set_read_timeout(READ_TIMEOUT) {
            return Err(err);
        }

        let mut addr: libc::sockaddr_can = unsafe { mem::zeroed() };
        addr.can_family = libc::AF_CAN as libc::sa_family_t;
        addr.can_ifindex = ifindex as libc::c_int;

        let bind_result = unsafe {
            libc::bind(
                socket.fd,
                &addr as *const libc::sockaddr_can as *const libc::sockaddr,
                mem::size_of::<libc::sockaddr_can>() as libc::socklen_t,
            )
        };
        if bind_result < 0 {
            return Err(io::Error::last_os_error());
        }

        Ok(socket)
    }

    fn set_read_timeout(&self, timeout: Duration) -> io::Result<()> {
        let tv = libc::timeval {
            tv_sec: timeout.as_secs() as libc::time_t,
            tv_usec: timeout.subsec_micros() as libc::suseconds_t,
        };
        let result = unsafe {
            libc::setsockopt(
                self.fd,
                libc::SOL_SOCKET,
                libc::SO_RCVTIMEO,
                &tv as *const libc::timeval as *const libc::c_void,
                mem::size_of::<libc::timeval>() as libc::socklen_t,
            )
        };
        if result < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    fn read_frame(&self) -> io::Result<Option<CanFrame>> {
        let mut frame: libc::can_frame = unsafe { mem::zeroed() };
        let read = unsafe {
            libc::read(
                self.fd,
                &mut frame as *mut libc::can_frame as *mut libc::c_void,
                mem::size_of::<libc::can_frame>(),
            )
        };

        if read < 0 {
            let err = io::Error::last_os_error();
            if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) {
                return Ok(None);
            }
            return Err(err);
        }
        if read == 0 {
            return Ok(None);
        }
        if read as usize != mem::size_of::<libc::can_frame>() {
            debug!("can: short frame read: {read} bytes");
            return Ok(None);
        }

        let len = usize::from(frame.can_dlc.min(8));
        Ok(Some(CanFrame {
            can_id: frame.can_id,
            len,
            data: frame.data,
        }))
    }
}

impl Drop for CanSocket {
    fn drop(&mut self) {
        unsafe {
            libc::close(self.fd);
        }
    }
}

fn decode_aem_uego(can_id: u32, data: &[u8]) -> Option<CanSample> {
    let is_extended = can_id & CAN_EFF_FLAG != 0;
    let id = can_id & CAN_EFF_MASK;
    if !is_extended || !(AEM_ID_START..=AEM_ID_END).contains(&id) {
        return None;
    }
    if data.len() < 8 {
        return Some(CanSample {
            can_present: true,
            can_frames_seen: 0,
            afr_valid: false,
            can_valid: true,
            afr: None,
            lambda: None,
            oxygen: None,
            volts: None,
            free_air: false,
            lambda_valid_flag: false,
            sensor_fault: true,
            status_text: "AEM UEGO frame too short".to_owned(),
        });
    }

    let lambda_raw = u16::from_be_bytes([data[0], data[1]]);
    let oxygen_raw = i16::from_be_bytes([data[2], data[3]]);
    let volts = data[4] as f32 * 0.1;
    let flags = data[6];
    let fault_flags = data[7];
    let free_air = flags & (1 << 5) != 0;
    let lambda_valid_flag = flags & (1 << 7) != 0;
    let sensor_fault = fault_flags & (1 << 6) != 0;
    let usable = lambda_raw != LAMBDA_FREE_AIR_RAW
        && !free_air
        && lambda_valid_flag
        && !sensor_fault
        && lambda_raw > 0;

    let lambda = (lambda_raw as f32) * 0.0001;
    let status_text = if sensor_fault {
        "AEM UEGO sensor fault".to_owned()
    } else if free_air || lambda_raw == LAMBDA_FREE_AIR_RAW {
        "Free air".to_owned()
    } else if !lambda_valid_flag {
        "AEM UEGO lambda invalid".to_owned()
    } else {
        "AEM UEGO active".to_owned()
    };

    Some(CanSample {
        can_present: true,
        can_frames_seen: 0,
        afr_valid: usable,
        can_valid: true,
        afr: usable.then_some(lambda * STOICH_AFR),
        lambda: usable.then_some(lambda),
        oxygen: Some(oxygen_raw as f32 * 0.001),
        volts: Some(volts),
        free_air,
        lambda_valid_flag,
        sensor_fault,
        status_text,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn aem_id(id: u32) -> u32 {
        CAN_EFF_FLAG | id
    }

    #[test]
    fn decodes_valid_aem_uego_frame() {
        let sample = decode_aem_uego(
            aem_id(0x180),
            &[0x27, 0x10, 0xFF, 0x9C, 0x32, 0x00, 0x80, 0x00],
        )
        .expect("aem frame");

        assert!(sample.can_present);
        assert!(sample.can_valid);
        assert!(sample.afr_valid);
        assert!((sample.lambda.unwrap() - 1.0).abs() < 0.0001);
        assert!((sample.afr.unwrap() - 14.65).abs() < 0.001);
        assert!((sample.oxygen.unwrap() + 0.100).abs() < 0.001);
        assert!((sample.volts.unwrap() - 5.0).abs() < 0.001);
    }

    #[test]
    fn free_air_does_not_expose_afr_96_as_valid() {
        let sample = decode_aem_uego(
            aem_id(0x181),
            &[0xFF, 0xFF, 0x00, 0x00, 0x10, 0x00, 0xA0, 0x00],
        )
        .expect("aem frame");

        assert!(sample.can_valid);
        assert!(!sample.afr_valid);
        assert_eq!(sample.afr, None);
        assert_eq!(sample.lambda, None);
        assert_eq!(sample.status_text, "Free air");
    }

    #[test]
    fn ignores_non_aem_or_standard_frames() {
        assert_eq!(decode_aem_uego(aem_id(0x200), &[0; 8]), None);
        assert_eq!(decode_aem_uego(0x180, &[0; 8]), None);
    }
}
