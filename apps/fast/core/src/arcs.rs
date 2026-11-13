//! The calendar's 24-hour day rings: each fast's pieces per day, and how a week row strokes them
//! as continuous loops.

use crate::archive::FastArchive;
use crate::time::{self, CalendarSpec};
use std::time::SystemTime;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DayArcKind {
    Actual,
    Running,
    Planned,
}

/// One piece of a fast, or of a planned fast, on one calendar day, as positions on a 24-hour dial:
/// 0 is midnight at the top, 0.5 noon at the bottom, clockwise, 1 the next midnight back at the top.
/// Positions are wall-clock times, so noon sits at the bottom on every day, daylight-saving days
/// included. The hours shown under a date stay `DaySummary.seconds` (actual elapsed time).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DayArc {
    pub fast_id: Uuid,
    pub kind: DayArcKind,
    /// Where the piece starts, 0…1. 0 when it comes in from the day before.
    pub from: f64,
    /// Where the piece ends, 0…1. 1 when it carries on past midnight.
    pub to: f64,
    /// The timer was already running at this day's first midnight.
    pub from_previous_day: bool,
    /// The timer ran past this day's last midnight. A fast that ends exactly at midnight doesn't.
    pub into_next_day: bool,
}

/// Wall-clock time of day as a fraction of 24 hours: 06:00 is 0.25, 18:00 is 0.75.
#[uniffi::export]
pub fn dial_position(date: SystemTime, calendar: &CalendarSpec) -> f64 {
    dial(date, &time::zone(calendar))
}

fn dial(date: SystemTime, tz: &jiff::tz::TimeZone) -> f64 {
    let Some(z) = time::zoned(date, tz) else { return 0.0 };
    let t = z.time();
    let seconds = t.hour() as f64 * 3_600.0 + t.minute() as f64 * 60.0 + t.second() as f64 + t.subsec_nanosecond() as f64 / 1e9;
    (seconds / 86_400.0).clamp(0.0, 1.0)
}

/// The pieces on `date`, earliest first. Planned fasts appear only while still ahead and only with
/// a goal (an open-ended plan has no end to draw). A running fast ends at `now`.
#[uniffi::export]
pub fn day_arcs(date: SystemTime, archive: &FastArchive, calendar: &CalendarSpec, now: SystemTime) -> Vec<DayArc> {
    let tz = time::zone(calendar);
    let Some((day_start, day_end)) = time::day_interval(date, &tz) else { return vec![] };
    let mut spans: Vec<(Uuid, DayArcKind, SystemTime, SystemTime)> = archive
        .fasts
        .iter()
        .map(|f| (f.id, if f.end.is_none() { DayArcKind::Running } else { DayArcKind::Actual }, f.start, f.end.unwrap_or(now)))
        .collect();
    for plan in archive.plans.iter().filter(|p| p.start >= now) {
        if let Some(minutes) = plan.goal_minutes.filter(|m| *m > 0) {
            spans.push((plan.id, DayArcKind::Planned, plan.start, time::add_seconds(plan.start, minutes as f64 * 60.0)));
        }
    }
    let mut arcs: Vec<DayArc> = spans
        .into_iter()
        .filter(|(_, _, start, end)| end > start && *start < day_end && *end > day_start)
        .map(|(id, kind, start, end)| {
            let comes_in = start < day_start;
            let goes_on = end > day_end;
            let from = if comes_in { 0.0 } else { dial(start, &tz) };
            let to = if goes_on || end == day_end { 1.0 } else { dial(end, &tz) };
            // On the day clocks go back, a short fast inside the repeated hour can read backwards
            // on the wall clock; keep it a sliver at its start rather than wrapping round the dial.
            DayArc { fast_id: id, kind, from, to: to.max(from), from_previous_day: comes_in, into_next_day: goes_on }
        })
        .collect();
    arcs.sort_by(|a, b| a.from.total_cmp(&b.from));
    arcs
}

/// One step of a week row's stroke. Geometry stays with the platform: rings sit in equal columns,
/// `day` is the column, positions are on the dial, "top" is the ring's midnight point.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum StrokeStep {
    /// Begin on `day`'s ring at `position`.
    StartOnRing { day: u32, position: f64 },
    /// Begin at the row's leading edge, level with the rings' tops (carried over from last week).
    StartAtLeadingEdge,
    /// Follow `day`'s ring clockwise from `from` to `to`.
    Arc { day: u32, from: f64, to: f64 },
    /// A straight line at the rings' top to `day`'s midnight point, or to the row's trailing edge.
    LineToTop { day: Option<u32> },
}

/// Where a later fast clears a small gap through an earlier one that came in through the same
/// midnight, so the two read as separate fasts. Clear (not paint) this arc on `day`, butt-capped,
/// 4 points / dp wider than the line, before stroking its fast.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StrokeGap {
    pub day: u32,
    pub from: f64,
    pub to: f64,
}

/// One fast's continuous stroke across a week row: one path, round caps and joins, dashed and
/// thinner when planned. A running fast that ends in this row gets an open dot at its end.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WeekStroke {
    pub fast_id: Uuid,
    pub kind: DayArcKind,
    pub steps: Vec<StrokeStep>,
    pub gaps: Vec<StrokeGap>,
    /// `(day, position)` of the running fast's end dot.
    pub end_dot_day: Option<u32>,
    pub end_dot_position: Option<f64>,
}

/// The week's strokes, given each day's `day_arcs` in column order, in the order fasts first
/// appear so an earlier fast is drawn before the one that follows it through the same midnight.
/// Draw them in order on one layer.
#[uniffi::export]
pub fn week_strokes(days: Vec<Vec<DayArc>>) -> Vec<WeekStroke> {
    let mut order: Vec<Uuid> = vec![];
    for pieces in &days {
        for piece in pieces {
            if !order.contains(&piece.fast_id) {
                order.push(piece.fast_id);
            }
        }
    }
    let count = days.len() as u32;
    order
        .into_iter()
        .map(|id| {
            let mut steps = vec![];
            let mut gaps = vec![];
            let mut kind = DayArcKind::Actual;
            let mut last: Option<(u32, &DayArc)> = None;
            for (index, pieces) in days.iter().enumerate() {
                let day = index as u32;
                let Some(piece) = pieces.iter().find(|p| p.fast_id == id) else { continue };
                kind = piece.kind;
                if steps.is_empty() {
                    if piece.from_previous_day {
                        steps.push(StrokeStep::StartAtLeadingEdge);
                        steps.push(StrokeStep::LineToTop { day: Some(day) });
                    } else {
                        steps.push(StrokeStep::StartOnRing { day, position: piece.from });
                    }
                }
                steps.push(StrokeStep::Arc { day, from: piece.from, to: piece.to });
                if piece.into_next_day {
                    steps.push(StrokeStep::LineToTop { day: if day + 1 < count { Some(day + 1) } else { None } });
                }
                // Another fast came in through this day's top and this one leaves through it.
                if piece.into_next_day
                    && !piece.from_previous_day
                    && pieces.iter().any(|p| p.fast_id != id && p.from_previous_day && !p.into_next_day)
                {
                    gaps.push(StrokeGap { day, from: piece.from, to: piece.to });
                }
                last = Some((day, piece));
            }
            let dot = last.filter(|(_, p)| kind == DayArcKind::Running && !p.into_next_day);
            WeekStroke {
                fast_id: id,
                kind,
                steps,
                gaps,
                end_dot_day: dot.map(|(d, _)| d),
                end_dot_position: dot.map(|(_, p)| p.to),
            }
        })
        .collect()
}
