use alarm_core::*;
use std::{io::Cursor, time::SystemTime};
fn wav(seconds: u32, channels: u16) -> Vec<u8> {
    let mut data = Cursor::new(Vec::new());
    { let mut writer = hound::WavWriter::new(&mut data, hound::WavSpec { channels, sample_rate: 8000, bits_per_sample: 16, sample_format: hound::SampleFormat::Int }).unwrap();
      for i in 0..seconds * 8000 * channels as u32 { writer.write_sample((i % 1000) as i16).unwrap(); } writer.finalize().unwrap(); }
    data.into_inner()
}
#[test] fn imported_sound_checks_all_samples_and_duration_without_trimming() {
    let data = wav(29, 2); let prepared = imported_sound_prepare(data.clone()).unwrap();
    assert_eq!(prepared.milliseconds, 29000);
    assert_eq!(hound::WavReader::new(Cursor::new(prepared.wav)).unwrap().duration(), 29 * 8000);
    assert!(imported_sound_prepare(wav(30, 1)).is_err());
    assert!(imported_sound_prepare(wav(0, 1)).is_err());
    assert!(imported_sound_prepare(wav(1, 3)).is_err());
    assert!(imported_sound_prepare(data[..data.len()-1].to_vec()).is_err());
    assert!(imported_sound_prepare(b"not audio".to_vec()).is_err());
    assert!(imported_sound_prepare(vec![0; imported_sound_max_bytes() as usize + 1]).is_err());
}
#[test] fn imported_sound_metadata_survives_backup_and_templates_stay_disabled() {
    let mut routine = new_routine(RoutineKind::Alarm, SystemTime::now());
    routine.imported_sound = Some(ImportedSound { id: uuid::Uuid::new_v4(), name: "Morning.wav".into() });
    let archive = backup_decode(&routine_template(&routine).unwrap()).unwrap();
    assert_eq!(archive.routines[0].imported_sound, routine.imported_sound);
    assert!(!archive.routines[0].enabled);
    routine.imported_sound.as_mut().unwrap().name = " ".into();
    assert!(routine_validate(&routine).is_err());
}

#[test] fn cleanup_keeps_pending_and_installed_sounds_until_receipts_are_cleared() {
    let mut routine = new_routine(RoutineKind::Alarm, SystemTime::now());
    let id = uuid::Uuid::new_v4();
    routine.imported_sound = Some(ImportedSound { id, name: "Clip.wav".into() });
    let row = new_registration(uuid::Uuid::new_v4(), routine.clone());
    let mut archive = LoopArchive { routines: vec![routine], installed: vec![row.clone()], pending: Some(row), ..LoopArchive::default() };
    assert_eq!(imported_sound_references(&archive), vec![id]);
    archive.routines.clear(); archive.installed.clear();
    assert_eq!(imported_sound_references(&archive), vec![id]);
    archive.pending = None;
    assert!(imported_sound_references(&archive).is_empty());
}
