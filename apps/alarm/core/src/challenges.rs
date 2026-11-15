//! Wake-up missions: answer checks and the stateful counters behind the memory and shake
//! missions. Sensors, the camera and every pixel stay native.

use crate::text::{nfc, trimmed};
use std::sync::Mutex;
use uuid::Uuid;

/// Swift `ArithmeticChallenge`: add two numbers from 2 to 25.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ArithmeticChallenge {
    pub left: i32,
    pub right: i32,
}

/// Swift `ArithmeticChallenge()`: both terms drawn uniformly from 2…25 with the system's secure
/// random source.
#[uniffi::export]
pub fn new_arithmetic_challenge() -> ArithmeticChallenge {
    let draw = || {
        // 2^128 mod 24 bias is far below anything a person could notice.
        (Uuid::new_v4().as_u128() % 24) as i32 + 2
    };
    ArithmeticChallenge { left: draw(), right: draw() }
}

/// Swift `ArithmeticChallenge.accepts`: the trimmed answer parsed as an integer equals the sum.
#[uniffi::export]
pub fn arithmetic_accepts(challenge: ArithmeticChallenge, answer: String) -> bool {
    trimmed(&answer).parse::<i64>().ok() == Some(challenge.left as i64 + challenge.right as i64)
}

/// Swift `MissionCheck.phrase`: trimmed, compared in NFC.
#[uniffi::export]
pub fn mission_check_phrase(actual: String, expected: String) -> bool {
    nfc(trimmed(&actual)) == nfc(trimmed(&expected))
}

/// Swift `MissionCheck.code`: exact (Swift `String ==`, canonical equivalence), never an empty code.
#[uniffi::export]
pub fn mission_check_code(actual: String, expected: String) -> bool {
    !expected.is_empty() && nfc(&actual) == nfc(&expected)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum ColorWord {
    Red,
    Blue,
    Green,
    Orange,
}

/// Swift `ColorWord.accepts`: the ink colour is the answer, not the word.
#[uniffi::export]
pub fn color_word_accepts(answer: ColorWord, ink: ColorWord) -> bool {
    answer == ink
}

/// Swift `SequenceChallenge`: tiles tapped in order; a wrong tap starts over.
#[derive(Debug, uniffi::Object)]
pub struct SequenceChallenge {
    sequence: Vec<i32>,
    progress: Mutex<usize>,
}

#[uniffi::export]
impl SequenceChallenge {
    #[uniffi::constructor]
    pub fn new(sequence: Vec<i32>) -> Self {
        Self { sequence, progress: Mutex::new(0) }
    }

    pub fn sequence(&self) -> Vec<i32> {
        self.sequence.clone()
    }

    pub fn progress(&self) -> u32 {
        *self.progress.lock().unwrap() as u32
    }

    pub fn complete(&self) -> bool {
        !self.sequence.is_empty() && *self.progress.lock().unwrap() == self.sequence.len()
    }

    /// Whether the tap was right. Taps after completion are refused.
    pub fn tap(&self, tile: i32) -> bool {
        let mut progress = self.progress.lock().unwrap();
        if self.sequence.is_empty() || *progress == self.sequence.len() {
            return false;
        }
        if tile != self.sequence[*progress] {
            *progress = 0;
            return false;
        }
        *progress += 1;
        true
    }
}

#[derive(Debug)]
struct Shake {
    count: u32,
    armed: bool,
    last_peak: f64,
}

/// Swift `ShakeCounter`: counts peaks above 1.1 g, re-armed by dropping under 0.35 g, at least
/// 0.35 s apart, so a sustained push counts once. Non-finite samples are ignored.
#[derive(Debug, uniffi::Object)]
pub struct ShakeCounter {
    state: Mutex<Shake>,
}

impl Default for ShakeCounter {
    fn default() -> Self {
        Self { state: Mutex::new(Shake { count: 0, armed: true, last_peak: f64::NEG_INFINITY }) }
    }
}

#[uniffi::export]
impl ShakeCounter {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self::default()
    }

    pub fn count(&self) -> u32 {
        self.state.lock().unwrap().count
    }

    pub fn sample(&self, acceleration: f64, timestamp: f64) {
        if !acceleration.is_finite() || !timestamp.is_finite() {
            return;
        }
        let mut s = self.state.lock().unwrap();
        if acceleration < 0.35 {
            s.armed = true;
        }
        if s.armed && acceleration > 1.1 && timestamp - s.last_peak >= 0.35 {
            s.count += 1;
            s.armed = false;
            s.last_peak = timestamp;
        }
    }
}
