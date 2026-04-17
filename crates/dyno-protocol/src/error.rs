use thiserror::Error;

/// Errors produced by the protocol layer.
///
/// `FrameDecoder::decode_next` only surfaces `InvalidLength` (internal
/// assertion failure) and `UnsupportedVersion` (returned by `DynoFrameV1::decode`).
/// CRC failures and de-sync are handled internally by the decoder; they never
/// propagate to the caller as `Err`.
#[derive(Debug, Error, PartialEq)]
pub enum ProtocolError {
    /// Byte slice passed to `DynoFrameV1::from_bytes` was shorter than `FRAME_SIZE`.
    #[error("invalid frame length: got {got}, expected {expected}")]
    InvalidLength { got: usize, expected: usize },

    /// CRC computed over frame bytes does not match the stored CRC field.
    /// Raised from `DynoFrameV1::verify_crc`; the decoder handles this
    /// internally and does not propagate it.
    #[error("CRC mismatch: computed {computed:#06x}, received {received:#06x}")]
    CrcMismatch { computed: u16, received: u16 },

    /// Magic header was not found or byte stream is unrecoverable.
    /// Returned only when the internal buffer is in an unrecoverable state.
    #[error("byte stream desync")]
    Desync,

    /// The `version` field in the frame header is not `1`.
    #[error("unsupported protocol version: {0}")]
    UnsupportedVersion(u8),
}
