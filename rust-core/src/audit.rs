struct BinReader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> BinReader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn read_u8(&mut self) -> Option<u8> {
        let b = *self.buf.get(self.pos)?;
        self.pos += 1;
        Some(b)
    }

    fn read_i32(&mut self) -> Option<i32> {
        let slice = self.buf.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(i32::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_u32(&mut self) -> Option<u32> {
        let slice = self.buf.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(u32::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_i64(&mut self) -> Option<i64> {
        let slice = self.buf.get(self.pos..self.pos + 8)?;
        self.pos += 8;
        Some(i64::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_str(&mut self) -> Option<&'a str> {
        let len = self.read_u32()? as usize;
        let slice = self.buf.get(self.pos..self.pos + len)?;
        self.pos += len;
        std::str::from_utf8(slice).ok()
    }
}

#[inline]
fn is_outside_working_hours(min_of_day: i32, start_min: i32, end_min: i32) -> bool {
    if start_min == end_min {
        return false;
    }
    let inside = if start_min < end_min {
        min_of_day >= start_min && min_of_day <= end_min
    } else {
        min_of_day >= start_min || min_of_day <= end_min
    };
    !inside
}

/// Exact zero-regex matcher for `^(\d{1,2}\s+[A-Za-z]{3})\s+(\d{2}):(\d{2})$`.
fn parse_time_prefix_minutes(time_part: &str) -> Option<i32> {
    let b = time_part.as_bytes();
    let n = b.len();
    let mut i = 0usize;

    // 1..2 ASCII digits (day)
    let d_start = i;
    while i < n && b[i].is_ascii_digit() && i - d_start < 2 {
        i += 1;
    }
    if i == d_start {
        return None;
    }

    // 1+ whitespace
    let ws1 = i;
    while i < n && b[i].is_ascii_whitespace() {
        i += 1;
    }
    if i == ws1 {
        return None;
    }

    // Exactly 3 ASCII letters (month)
    if i + 3 > n || !b[i..i + 3].iter().all(|c| c.is_ascii_alphabetic()) {
        return None;
    }
    i += 3;

    // 1+ whitespace
    let ws2 = i;
    while i < n && b[i].is_ascii_whitespace() {
        i += 1;
    }
    if i == ws2 {
        return None;
    }

    // Exactly HH:MM at end of string
    if i + 5 != n || b[i + 2] != b':' {
        return None;
    }
    if !b[i].is_ascii_digit()
        || !b[i + 1].is_ascii_digit()
        || !b[i + 3].is_ascii_digit()
        || !b[i + 4].is_ascii_digit()
    {
        return None;
    }
    let hh = ((b[i] - b'0') as i32) * 10 + ((b[i + 1] - b'0') as i32);
    let mm = ((b[i + 3] - b'0') as i32) * 10 + ((b[i + 4] - b'0') as i32);
    if (0..=23).contains(&hh) && (0..=59).contains(&mm) {
        Some(hh * 60 + mm)
    } else {
        None
    }
}

#[inline]
fn epoch_to_min_of_day(ts_ms: i64, tz_offset_ms: i64) -> i32 {
    let local_ms = ts_ms + tz_offset_ms;
    let ms_in_day = local_ms.rem_euclid(86_400_000);
    (ms_in_day / 60_000) as i32
}

/// Exact parity with PinEditHistoryAudit.countOutsideWorkingHours.
/// Wire: [i32 work_start_min][i32 work_end_min][i64 tz_offset_ms][u32 count]
///       count * ([str edit_history][u8 touched_fallback][i64 edited_at][i64 updated_at])
pub fn count_outside_working_hours(payload: &[u8]) -> Option<i32> {
    let mut r = BinReader::new(payload);
    let start_min = r.read_i32()?;
    let end_min = r.read_i32()?;
    let tz_offset_ms = r.read_i64()?;
    let count = r.read_u32()? as usize;
    if start_min == end_min {
        return Some(0);
    }

    let mut late = 0i32;
    for _ in 0..count {
        let hist = r.read_str()?;
        let touched_fallback = r.read_u8()? != 0;
        let edited_at = r.read_i64()?;
        let updated_at = r.read_i64()?;

        if !hist.trim().is_empty() {
            let mut idx = 0usize;
            for raw in hist.split('\n') {
                let line = raw.trim();
                if line.is_empty() {
                    continue;
                }
                let time_part = match line.find('•') {
                    Some(pos) if pos > 0 => line[..pos].trim(),
                    _ => "",
                };
                let min_of_day = parse_time_prefix_minutes(time_part).or_else(|| {
                    if idx == 0 && edited_at > 0 {
                        Some(epoch_to_min_of_day(edited_at, tz_offset_ms))
                    } else {
                        None
                    }
                });
                if let Some(m) = min_of_day {
                    if is_outside_working_hours(m, start_min, end_min) {
                        late += 1;
                    }
                }
                idx += 1;
            }
        } else if touched_fallback {
            let ts = if edited_at > 0 {
                edited_at
            } else if updated_at > 0 {
                updated_at
            } else {
                0
            };
            if ts > 0 {
                let m = epoch_to_min_of_day(ts, tz_offset_ms);
                if is_outside_working_hours(m, start_min, end_min) {
                    late += 1;
                }
            }
        }
    }

    Some(late)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_str(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
        buf.extend_from_slice(s.as_bytes());
    }

    #[test]
    fn counts_after_hours_history_and_overnight_windows() {
        let mut buf = Vec::new();
        buf.extend_from_slice(&(8 * 60i32).to_le_bytes()); // 08:00
        buf.extend_from_slice(&(17 * 60i32).to_le_bytes()); // 17:00
        buf.extend_from_slice(&(3 * 3600 * 1000i64).to_le_bytes()); // UTC+3
        buf.extend_from_slice(&2u32.to_le_bytes());

        // Pin 1: 1 in-hours line (10:15) + 1 after-hours line (19:30)
        put_str(&mut buf, "27 Nov 19:30 • Team 1 → Done\n27 Nov 10:15 • Team 1 → Issue");
        buf.push(1u8);
        buf.extend_from_slice(&0i64.to_le_bytes());
        buf.extend_from_slice(&0i64.to_le_bytes());

        // Pin 2: blank history, edited at 20:00 UTC+3 (17:00 UTC = 61200000 ms)
        put_str(&mut buf, "");
        buf.push(1u8);
        buf.extend_from_slice(&(17 * 3600 * 1000i64).to_le_bytes());
        buf.extend_from_slice(&0i64.to_le_bytes());

        assert_eq!(count_outside_working_hours(&buf), Some(2));
    }
}
