//! Streaming frame decoder for the ESP32 UART byte stream.
//!
//! # Design
//!
//! `FrameDecoder` maintains an internal growable byte buffer plus a `head`
//! cursor marking the start of unprocessed data.  Advancing the cursor is
//! O(1); physical memory is reclaimed lazily via [`FrameDecoder::maybe_compact`].
//!
//! The caller feeds raw bytes via [`FrameDecoder::feed`] then drains decoded
//! frames by calling [`FrameDecoder::decode_next`] in a loop until it returns
//! [`DecodeStatus::NeedMoreData`] or [`DecodeStatus::BudgetExhausted`].
//!
//! # Resync strategy
//!
//! 1. Scan `buffer[head..]` for the two-byte magic `[0x59, 0x44]`.
//! 2. Advance `head` past any leading garbage bytes.
//! 3. Once `FRAME_SIZE` bytes are available from `head`:
//!    - Compute CRC over `buffer[head .. head + CRC_OFFSET]`.
//!    - Compare against the stored CRC in `buffer[head + CRC_OFFSET ..]`.
//! 4. **CRC valid** → sanity-check version and packet_type, parse, emit.
//! 5. **CRC invalid or sanity fail** → `head += 1`; loop to find next magic.
//!
//! # Performance properties
//!
//! | Operation | Complexity |
//! |-----------|------------|
//! | `feed(n bytes)` | O(n) amortised (one `extend_from_slice`) |
//! | advance past garbage | O(1) per byte (cursor increment only) |
//! | CRC failure resync | O(1) per step (cursor increment) |
//! | emit valid frame | O(FRAME_SIZE) for the CRC + parse |
//! | compaction | O(remaining) when triggered |
//!
//! # Bounds
//!
//! * Buffer is capped at [`MAX_BUFFER_BYTES`].  On overflow the oldest bytes
//!   are dropped, keeping the last `FRAME_SIZE - 1` bytes so a partial frame
//!   at the boundary is not silently split.
//! * Each `decode_next` call processes at most [`MAX_SCAN_STEPS`] loop
//!   iterations, returning [`DecodeStatus::BudgetExhausted`] if the limit is
//!   hit.  The caller should yield to the async runtime and call again; frames
//!   may still be present in the buffer.

use crate::{
    frame::{crc16_ccitt, CRC_OFFSET, FRAME_SIZE, MAGIC_BYTES},
    DynoFrameV1, PacketType, ProtocolError,
};

/// Maximum bytes held in the internal buffer before oldest data is discarded.
const MAX_BUFFER_BYTES: usize = 8192;

/// Maximum loop iterations in a single `decode_next` call.
const MAX_SCAN_STEPS: usize = 256;

// ── DecodeStatus ──────────────────────────────────────────────────────────────

/// Result of a single [`FrameDecoder::decode_next`] call.
///
/// The two "no frame" variants carry different semantics:
///
/// * [`NeedMoreData`][DecodeStatus::NeedMoreData] — the buffer is genuinely
///   exhausted; the caller must feed more bytes before calling again.
/// * [`BudgetExhausted`][DecodeStatus::BudgetExhausted] — the per-call scan
///   budget was hit; frames **may still be present** in the buffer.  The caller
///   should yield to the async runtime (e.g. `tokio::task::yield_now().await`)
///   and then call `decode_next` again without feeding new bytes.
#[derive(Debug, PartialEq)]
pub enum DecodeStatus {
    /// A complete, CRC-validated frame was decoded and the cursor advanced.
    Frame(DynoFrameV1),
    /// No complete frame is available; more bytes must be fed first.
    NeedMoreData,
    /// The per-call scan budget was exhausted.  More frames may remain.
    BudgetExhausted,
}

impl DecodeStatus {
    /// Extract the inner frame, returning `None` for the two non-frame variants.
    ///
    /// Useful in tests and in callers that treat both non-frame cases the same.
    pub fn into_frame(self) -> Option<DynoFrameV1> {
        match self {
            Self::Frame(f) => Some(f),
            _              => None,
        }
    }

    /// `true` when this call produced no frame (either non-frame variant).
    pub fn is_done(&self) -> bool {
        !matches!(self, Self::Frame(_))
    }

    /// `true` specifically when the scan budget was exhausted.
    pub fn is_budget_exhausted(&self) -> bool {
        matches!(self, Self::BudgetExhausted)
    }

    /// `true` specifically when the buffer needs more data.
    pub fn is_need_more_data(&self) -> bool {
        matches!(self, Self::NeedMoreData)
    }
}

// ── FrameDecoder ──────────────────────────────────────────────────────────────

/// Stateful streaming decoder for `DynoFrameV1` frames.
pub struct FrameDecoder {
    /// Raw byte accumulator.  Valid (unprocessed) data lives at `[head..]`.
    buffer: Vec<u8>,
    /// Read cursor: index of the first unprocessed byte in `buffer`.
    head: usize,
}

impl FrameDecoder {
    /// Construct a decoder with a pre-allocated internal buffer.
    pub fn new() -> Self {
        Self {
            buffer: Vec::with_capacity(FRAME_SIZE * 8),
            head: 0,
        }
    }

    /// Append raw bytes received from the UART.
    ///
    /// Compacts the buffer first (if warranted), then extends.  If the result
    /// exceeds [`MAX_BUFFER_BYTES`], the oldest bytes are dropped, keeping
    /// `FRAME_SIZE - 1` bytes so a frame cannot be split at the trim boundary.
    ///
    /// Call [`FrameDecoder::decode_next`] in a loop after each feed.
    pub fn feed(&mut self, bytes: &[u8]) {
        // Reclaim dead space before growing.
        self.maybe_compact();

        self.buffer.extend_from_slice(bytes);

        // Hard cap: if the buffer is still oversized after compaction + feed,
        // drop the oldest bytes.  This can only happen under sustained noise
        // faster than the decoder can consume frames.
        if self.buffer.len() > MAX_BUFFER_BYTES {
            let keep  = FRAME_SIZE - 1;
            let start = self.buffer.len().saturating_sub(keep);
            self.buffer.copy_within(start.., 0);
            self.buffer.truncate(keep);
            self.head = 0;
        }
    }

    /// Attempt to decode the next complete, CRC-validated frame.
    ///
    /// # Returns
    ///
    /// | Value | Meaning |
    /// |-------|---------|
    /// | `Ok(DecodeStatus::Frame(f))` | A valid frame was decoded; cursor advanced. |
    /// | `Ok(DecodeStatus::NeedMoreData)` | Buffer exhausted; feed more bytes. |
    /// | `Ok(DecodeStatus::BudgetExhausted)` | Scan budget hit; yield and call again. |
    /// | `Err(InvalidLength)` | Internal invariant violated (should not occur). |
    #[must_use = "decoded frames must not be silently discarded"]
    pub fn decode_next(&mut self) -> Result<DecodeStatus, ProtocolError> {
        let mut steps = 0;

        loop {
            // ── Budget guard ─────────────────────────────────────────────────
            if steps >= MAX_SCAN_STEPS {
                return Ok(DecodeStatus::BudgetExhausted);
            }
            steps += 1;

            // ── Step 1: locate magic in unprocessed window ───────────────────
            let window = &self.buffer[self.head..];

            let magic_offset = match window.windows(2).position(|w| w == MAGIC_BYTES) {
                Some(pos) => pos,
                None => {
                    // No magic found.  Retain the last byte: it could be the
                    // first byte of a magic pair split across two feed calls.
                    if window.len() > 1 {
                        self.head = self.buffer.len() - 1;
                    }
                    return Ok(DecodeStatus::NeedMoreData);
                }
            };

            // ── Step 2: skip garbage before magic ────────────────────────────
            self.head += magic_offset;

            // ── Step 3: wait for a full frame ────────────────────────────────
            if self.buffer.len() - self.head < FRAME_SIZE {
                return Ok(DecodeStatus::NeedMoreData);
            }

            let fs = self.head; // frame start index

            // ── Step 4: CRC check ────────────────────────────────────────────
            let computed = crc16_ccitt(&self.buffer[fs..fs + CRC_OFFSET]);
            let received = u16::from_le_bytes([
                self.buffer[fs + CRC_OFFSET],
                self.buffer[fs + CRC_OFFSET + 1],
            ]);

            if computed != received {
                // False magic or corrupted frame — advance one byte and retry.
                self.head += 1;
                continue;
            }

            // ── Step 5: lightweight sanity check ────────────────────────────
            // Reject frames with unknown version or unrecognised packet type
            // even when CRC passes (e.g. firmware version mismatch).
            let version = self.buffer[fs + 2];
            let ptype = PacketType::from(self.buffer[fs + 3]);
            if version != 1 || !ptype.is_telemetry() {
                self.head += 1;
                continue;
            }

            // ── Step 6: parse and emit ────────────────────────────────────────
            let frame = DynoFrameV1::from_bytes(&self.buffer[fs..fs + FRAME_SIZE])?;
            self.head = fs + FRAME_SIZE;
            return Ok(DecodeStatus::Frame(frame));
        }
    }

    /// Number of unprocessed bytes currently held in the buffer.
    ///
    /// Returns `0` when all received bytes have been consumed by successful
    /// `decode_next` calls.
    pub fn buffered_len(&self) -> usize {
        self.buffer.len() - self.head
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /// Copy unprocessed bytes to the front of `buffer` and reset `head` to 0
    /// when either of these conditions holds:
    ///
    /// * `head > 1024` — more than 1 KiB of dead space at the front.
    /// * `head > buffer.len() / 2` — more than half the buffer is dead.
    ///
    /// Uses `copy_within` (a single `memmove`) rather than reallocating.
    fn maybe_compact(&mut self) {
        if self.head == 0 {
            return;
        }
        let len = self.buffer.len();
        if self.head > 1024 || self.head > len / 2 {
            let remaining = len - self.head;
            self.buffer.copy_within(self.head.., 0);
            self.buffer.truncate(remaining);
            self.head = 0;
        }
    }
}

impl Default for FrameDecoder {
    fn default() -> Self {
        Self::new()
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::tests::make_frame_bytes;
    use crate::frame::{CRC_OFFSET, FRAME_SIZE};

    // ── A: valid frame ────────────────────────────────────────────────────────

    #[test]
    fn valid_frame_decoded_correctly() {
        let bytes = make_frame_bytes(42, 1_000_000, 8333);
        let mut dec = FrameDecoder::new();
        dec.feed(&bytes);
        let frame = dec.decode_next().unwrap().into_frame().expect("expected a frame");

        assert_eq!(frame.magic, 0x4459);
        assert_eq!(frame.version, 1);
        assert_eq!(frame.packet_type, 0x01);
        assert_eq!(frame.seq, 42);
        assert_eq!(frame.ts_us, 1_000_000);
        assert_eq!(frame.engine_period_us, 8333);
        assert_eq!(frame.afr_scaled_x100, 1380);
        assert_eq!(frame.lambda_scaled_x1000, 939);
        assert_eq!(dec.buffered_len(), 0);
    }

    #[test]
    fn decoded_physical_units() {
        let bytes = make_frame_bytes(1, 0, 0);
        let mut dec = FrameDecoder::new();
        dec.feed(&bytes);
        let raw = dec.decode_next().unwrap().into_frame().unwrap();
        let decoded = raw.decode().unwrap();

        assert!((decoded.afr - 13.80).abs() < 1e-4, "afr={}", decoded.afr);
        assert!((decoded.lambda - 0.939).abs() < 1e-4, "lambda={}", decoded.lambda);
    }

    // ── B: corrupted CRC ─────────────────────────────────────────────────────

    #[test]
    fn corrupted_crc_frame_rejected() {
        let mut bytes = make_frame_bytes(1, 0, 0).to_vec();
        bytes[10] ^= 0xFF;

        let mut dec = FrameDecoder::new();
        dec.feed(&bytes);
        let result = dec.decode_next().unwrap();
        assert!(result.is_done(), "corrupted frame must not be accepted");
    }

    #[test]
    fn flipped_crc_field_rejected() {
        let mut bytes = make_frame_bytes(1, 0, 0).to_vec();
        bytes[CRC_OFFSET] ^= 0x01;

        let mut dec = FrameDecoder::new();
        dec.feed(&bytes);
        assert!(dec.decode_next().unwrap().is_done());
    }

    // ── C: desync recovery ────────────────────────────────────────────────────

    #[test]
    fn garbage_prefix_skipped() {
        let noise: Vec<u8> = (0u8..20).map(|i| i.wrapping_mul(0x37)).collect();
        let valid = make_frame_bytes(7, 500, 12000);
        let mut stream = noise;
        stream.extend_from_slice(&valid);

        let mut dec = FrameDecoder::new();
        dec.feed(&stream);
        let frame = dec.decode_next().unwrap().into_frame().expect("should recover valid frame");
        assert_eq!(frame.seq, 7);
        assert_eq!(dec.buffered_len(), 0);
    }

    #[test]
    fn false_magic_in_noise_then_valid_frame() {
        let mut noise: Vec<u8> = vec![0xAA; 10];
        noise.extend_from_slice(&[0x59, 0x44]); // false magic
        noise.extend_from_slice(&[0xBB; 10]);
        noise.extend_from_slice(&make_frame_bytes(99, 9999, 5000));

        let mut dec = FrameDecoder::new();
        dec.feed(&noise);
        let frame = dec.decode_next().unwrap().into_frame().expect("should emit valid frame");
        assert_eq!(frame.seq, 99);
    }

    // ── D: split frame ────────────────────────────────────────────────────────

    #[test]
    fn frame_split_across_two_feeds() {
        let bytes = make_frame_bytes(3, 200, 16666);

        let mut dec = FrameDecoder::new();
        dec.feed(&bytes[..FRAME_SIZE / 2]);
        assert!(dec.decode_next().unwrap().is_done());

        dec.feed(&bytes[FRAME_SIZE / 2..]);
        let frame = dec.decode_next().unwrap().into_frame().expect("should decode after second feed");
        assert_eq!(frame.seq, 3);
    }

    #[test]
    fn frame_fed_one_byte_at_a_time() {
        let bytes = make_frame_bytes(5, 300, 6250);
        let mut dec = FrameDecoder::new();
        let mut found = None;

        for byte in &bytes {
            dec.feed(std::slice::from_ref(byte));
            if let Some(f) = dec.decode_next().unwrap().into_frame() {
                found = Some(f);
                break;
            }
        }
        let frame = found.expect("frame must be decoded byte by byte");
        assert_eq!(frame.seq, 5);
    }

    // ── E: multiple frames in a single feed ──────────────────────────────────

    #[test]
    fn multiple_frames_decoded_in_sequence() {
        let mut stream = Vec::new();
        for i in 0u32..5 {
            stream.extend_from_slice(&make_frame_bytes(i, i * 10_000, 8000));
        }

        let mut dec = FrameDecoder::new();
        dec.feed(&stream);

        for expected_seq in 0u32..5 {
            let frame = dec.decode_next().unwrap().into_frame().expect("should emit frame");
            assert_eq!(frame.seq, expected_seq, "seq mismatch at index {expected_seq}");
        }
        assert!(dec.decode_next().unwrap().is_done(), "buffer should be empty");
        assert_eq!(dec.buffered_len(), 0);
    }

    #[test]
    fn valid_frames_after_corrupted_frame() {
        let mut corrupted = make_frame_bytes(0, 0, 0).to_vec();
        corrupted[15] ^= 0xFF;

        let mut stream = corrupted;
        stream.extend_from_slice(&make_frame_bytes(1, 1000, 7500));
        stream.extend_from_slice(&make_frame_bytes(2, 2000, 7500));

        let mut dec = FrameDecoder::new();
        dec.feed(&stream);

        let f1 = dec.decode_next().unwrap().into_frame().expect("first good frame");
        let f2 = dec.decode_next().unwrap().into_frame().expect("second good frame");
        assert_eq!(f1.seq, 1);
        assert_eq!(f2.seq, 2);
        assert!(dec.decode_next().unwrap().is_done());
    }

    // ── F: buffer limit ───────────────────────────────────────────────────────

    #[test]
    fn large_garbage_does_not_grow_buffer_unbounded() {
        let mut dec = FrameDecoder::new();

        let garbage = vec![0xABu8; MAX_BUFFER_BYTES + 1000];
        dec.feed(&garbage);

        assert!(
            dec.buffered_len() <= FRAME_SIZE - 1,
            "buffer must be capped after overflow; buffered_len={}",
            dec.buffered_len()
        );

        assert!(dec.decode_next().unwrap().is_done());
    }

    #[test]
    fn buffer_cap_then_valid_frame_decoded() {
        let mut dec = FrameDecoder::new();
        dec.feed(&vec![0x00u8; MAX_BUFFER_BYTES + 100]);

        let valid = make_frame_bytes(77, 42, 9000);
        dec.feed(&valid);

        let frame = dec.decode_next().unwrap().into_frame().expect("frame after buffer overflow");
        assert_eq!(frame.seq, 77);
    }

    // ── G: compaction ─────────────────────────────────────────────────────────

    #[test]
    fn compaction_fires_after_head_exceeds_threshold() {
        let mut dec = FrameDecoder::new();

        let mut stream = Vec::new();
        for i in 0u32..30 {
            stream.extend_from_slice(&make_frame_bytes(i, i * 10_000, 8000));
        }
        dec.feed(&stream);
        for _ in 0..30 {
            dec.decode_next().unwrap().into_frame().expect("frame expected");
        }

        assert_eq!(dec.buffered_len(), 0);

        let extra = make_frame_bytes(999, 0, 0);
        dec.feed(&extra);

        let frame = dec.decode_next().unwrap().into_frame().expect("frame after compaction");
        assert_eq!(frame.seq, 999);
        assert_eq!(dec.buffered_len(), 0);
    }

    #[test]
    fn interleaved_feed_and_decode_stays_bounded() {
        let mut dec = FrameDecoder::new();

        for i in 0u32..500 {
            dec.feed(&make_frame_bytes(i, i * 1000, 8000));
            let frame = dec.decode_next().unwrap().into_frame().expect("frame on every cycle");
            assert_eq!(frame.seq, i);
            assert_eq!(dec.buffered_len(), 0);
        }
    }

    // ── H: sanity check rejects unknown version / packet_type ────────────────

    #[test]
    fn frame_with_unknown_version_rejected_after_crc_pass() {
        let mut buf = make_frame_bytes(1, 0, 0).to_vec();
        buf[2] = 2;
        let crc = crc16_ccitt(&buf[..CRC_OFFSET]);
        buf[CRC_OFFSET..].copy_from_slice(&crc.to_le_bytes());

        let mut dec = FrameDecoder::new();
        dec.feed(&buf);
        assert!(
            dec.decode_next().unwrap().is_done(),
            "frame with unknown version must be rejected even when CRC passes"
        );
    }

    #[test]
    fn frame_with_unknown_packet_type_rejected_after_crc_pass() {
        let mut buf = make_frame_bytes(1, 0, 0).to_vec();
        buf[3] = 0xFF;
        let crc = crc16_ccitt(&buf[..CRC_OFFSET]);
        buf[CRC_OFFSET..].copy_from_slice(&crc.to_le_bytes());

        let mut dec = FrameDecoder::new();
        dec.feed(&buf);
        assert!(
            dec.decode_next().unwrap().is_done(),
            "frame with unknown packet_type must be rejected even when CRC passes"
        );
    }

    // ── I: DecodeStatus semantics ─────────────────────────────────────────────

    #[test]
    fn need_more_data_on_incomplete_frame() {
        let bytes = make_frame_bytes(1, 0, 0);
        let mut dec = FrameDecoder::new();
        // Feed only part of a frame.
        dec.feed(&bytes[..FRAME_SIZE - 1]);
        let status = dec.decode_next().unwrap();
        assert_eq!(status, DecodeStatus::NeedMoreData);
        assert!(status.is_need_more_data());
        assert!(!status.is_budget_exhausted());
    }

    #[test]
    fn budget_exhausted_returns_distinct_variant() {
        // Densely pack [0x59, 0x44] pairs so that every 1-2 bytes there is a
        // new magic candidate, causing rapid scan-step consumption.  Each 2-byte
        // block fits easily within MAX_BUFFER_BYTES (600 bytes << 8192) while
        // providing far more than MAX_SCAN_STEPS (256) candidates.
        let stream: Vec<u8> = std::iter::repeat([0x59u8, 0x44u8])
            .take(300)
            .flatten()
            .collect();

        let mut dec = FrameDecoder::new();
        dec.feed(&stream);

        // The first call should hit the budget before exhausting all candidates.
        let status = dec.decode_next().unwrap();
        assert_eq!(status, DecodeStatus::BudgetExhausted);
        assert!(status.is_budget_exhausted());
        assert!(!status.is_need_more_data());

        // Calling again continues from where it left off (cursor preserved).
        // There is still data buffered.
        assert!(dec.buffered_len() > 0);
    }

    #[test]
    fn budget_exhausted_cursor_preserved_across_calls() {
        // Build a stream: dense [0x59, 0x44] pairs (600 bytes, << 8192 cap)
        // followed by one valid frame.  The decoder must exhaust its budget
        // one or more times before reaching the valid frame.
        let mut stream: Vec<u8> = std::iter::repeat([0x59u8, 0x44u8])
            .take(300)
            .flatten()
            .collect();
        stream.extend_from_slice(&make_frame_bytes(42, 0, 0));

        let mut dec = FrameDecoder::new();
        dec.feed(&stream);

        // Call decode_next repeatedly until we get the valid frame.
        // Each call may return BudgetExhausted or the frame; never panics.
        let mut found = None;
        for _ in 0..20 {
            match dec.decode_next().unwrap() {
                DecodeStatus::Frame(f)      => { found = Some(f); break; }
                DecodeStatus::BudgetExhausted => continue,
                DecodeStatus::NeedMoreData    => break,
            }
        }
        let frame = found.expect("valid frame must eventually be decoded");
        assert_eq!(frame.seq, 42);
    }
}
