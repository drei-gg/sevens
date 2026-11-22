//! Imported alarm clips use a small, portable PCM WAV subset. Hound parses and rewrites the
//! container: user metadata and filenames never become native playback paths.
use crate::{LoopError, model::invalid};
use std::io::Cursor;

pub const SOUND_MAX_BYTES: u64 = 6 * 1024 * 1024;
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ImportedSoundData {
    pub wav: Vec<u8>,
    pub milliseconds: u32,
}
#[uniffi::export]
pub fn imported_sound_max_bytes() -> u64 { SOUND_MAX_BYTES }
#[uniffi::export]
pub fn imported_sound_prepare(data: Vec<u8>) -> Result<ImportedSoundData, LoopError> {
    if data.len() as u64 > SOUND_MAX_BYTES { return Err(invalid("Choose a WAV file no larger than 6 MB.")); }
    let mut reader = hound::WavReader::new(Cursor::new(data)).map_err(|_| invalid("Choose a readable PCM WAV file."))?;
    let spec = reader.spec();
    if spec.sample_format != hound::SampleFormat::Int || spec.bits_per_sample != 16 || !(1..=2).contains(&spec.channels) || !(8000..=48000).contains(&spec.sample_rate) {
        return Err(invalid("Use 16-bit PCM WAV, mono or stereo, at 8–48 kHz."));
    }
    let frames = reader.duration();
    if frames == 0 || frames > spec.sample_rate * 29 { return Err(invalid("Choose a nonempty sound up to 29 seconds. Longer sounds are not trimmed.")); }
    let mut output = Cursor::new(Vec::new());
    {
        let mut writer = hound::WavWriter::new(&mut output, spec).map_err(|_| invalid("Could not prepare this sound."))?;
        for sample in reader.samples::<i16>() {
            writer.write_sample(sample.map_err(|_| invalid("This WAV file is incomplete or damaged."))?).map_err(|_| invalid("Could not prepare this sound."))?;
        }
        writer.finalize().map_err(|_| invalid("Could not prepare this sound."))?;
    }
    Ok(ImportedSoundData { wav: output.into_inner(), milliseconds: ((frames as u64 * 1000).div_ceil(spec.sample_rate as u64)) as u32 })
}

/// Any saved or in-flight registration can still need its owned sound during reconciliation.
#[uniffi::export]
pub fn imported_sound_references(archive: &crate::LoopArchive) -> Vec<uuid::Uuid> {
    let mut seen = std::collections::HashSet::new();
    archive.routines.iter().chain(archive.installed.iter().map(|row| &row.routine))
        .chain(archive.pending.iter().map(|row| &row.routine))
        .filter_map(|routine| routine.imported_sound.as_ref().map(|sound| sound.id))
        .filter(|id| seen.insert(*id)).collect()
}
