//! A portable routine is an ordinary dormant backup, without device state or history.
use crate::{LoopArchive, LoopError, Routine, backup_encode, routine_validate};

/// Explicit export includes the selected routine's name, reason and mission phrase/code.
/// Fresh identities allow importing a copy on the originating device; reimporting the same
/// saved file is idempotent through the ordinary backup merge. It never arms an alarm.
#[uniffi::export]
pub fn routine_template(routine: &Routine) -> Result<Vec<u8>, LoopError> {
    routine_validate(routine)?;
    let mut copy = routine.clone();
    copy.id = uuid::Uuid::new_v4();
    copy.enabled = false;
    for slot in &mut copy.slots {
        slot.id = uuid::Uuid::new_v4();
    }
    backup_encode(&LoopArchive {
        routines: vec![copy],
        ..LoopArchive::default()
    })
}
