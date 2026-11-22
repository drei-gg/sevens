//! Similar shots as connected groups. The capture-time window (platform.rs) yields nearby pairs;
//! a burst of N photos used to become N−1 overlapping pairs. Pairs below the measure's threshold
//! are joined into groups, closest first, and no group grows past a size a person can compare.
//! Suggestions for review only: similar groups are never preselected.

use crate::library::{MatchGroup, MatchKind};
use crate::perceptual::SIMILAR_HASH_DISTANCE;
use crate::platform::SIMILAR_DISTANCE;
use std::collections::HashMap;
use std::sync::Mutex;

/// The most photos one similar group holds; the closest pairs join first.
pub const SIMILAR_GROUP_LIMIT: usize = 20;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum SimilarityMeasure {
    /// Vision feature-print distance (iOS); similar below 0.2.
    Vision,
    /// Perceptual-hash Hamming distance in 64 bits (Android); similar at 10 or less.
    ImageHash,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SimilarEdge {
    pub earlier: String,
    pub later: String,
    pub distance: f32,
}

/// Whether a pair at this distance is similar under the measure.
#[uniffi::export]
pub fn similar_edge_qualifies(distance: f32, measure: SimilarityMeasure) -> bool {
    match measure {
        SimilarityMeasure::Vision => distance < SIMILAR_DISTANCE,
        SimilarityMeasure::ImageHash => {
            distance.is_finite() && distance >= 0.0 && distance <= SIMILAR_HASH_DISTANCE as f32
        }
    }
}

fn find(parent: &mut [usize], mut i: usize) -> usize {
    while parent[i] != i {
        parent[i] = parent[parent[i]];
        i = parent[i];
    }
    i
}

fn reason(measure: SimilarityMeasure, members: usize, widest: f32) -> String {
    match (measure, members) {
        (SimilarityMeasure::Vision, 2) => format!(
            "Nearby capture times and Vision distance {:.3}. Inspect both; different moments may look alike.",
            widest as f64
        ),
        (SimilarityMeasure::Vision, _) => format!(
            "Nearby capture times. Each photo is within Vision distance {:.3} of another here. Inspect each; different moments may look alike.",
            widest as f64
        ),
        (SimilarityMeasure::ImageHash, 2) => format!(
            "Nearby capture times and an image-hash distance of {} in 64. Inspect both; different moments may look alike.",
            widest.round() as u32
        ),
        (SimilarityMeasure::ImageHash, _) => format!(
            "Nearby capture times. Each photo is within an image-hash distance of {} in 64 of another here. Inspect each; different moments may look alike.",
            widest.round() as u32
        ),
    }
}

/// Connected groups over the qualifying pairs: closest pairs join first, a join that would pass
/// [`SIMILAR_GROUP_LIMIT`] is skipped, and members follow `order` (the order photos were visited).
/// Ids missing from `order` follow it in the order they first appear.
#[uniffi::export]
pub fn similar_clusters(
    order: Vec<String>,
    edges: Vec<SimilarEdge>,
    measure: SimilarityMeasure,
) -> Vec<MatchGroup> {
    let mut index: HashMap<String, usize> = HashMap::new();
    let mut ids: Vec<String> = Vec::new();
    let mut intern = |id: &String, index: &mut HashMap<String, usize>| -> usize {
        if let Some(&i) = index.get(id) {
            return i;
        }
        ids.push(id.clone());
        index.insert(id.clone(), ids.len() - 1);
        ids.len() - 1
    };
    for id in &order {
        intern(id, &mut index);
    }
    let mut pairs: Vec<(usize, usize, f32)> = edges
        .iter()
        .filter(|e| e.earlier != e.later && similar_edge_qualifies(e.distance, measure))
        .map(|e| {
            let a = intern(&e.earlier, &mut index);
            let b = intern(&e.later, &mut index);
            (a, b, e.distance)
        })
        .collect();
    pairs.sort_by(|a, b| a.2.total_cmp(&b.2));
    let n = ids.len();
    let mut parent: Vec<usize> = (0..n).collect();
    let mut size = vec![1usize; n];
    let mut widest = vec![0f32; n];
    for (a, b, distance) in pairs {
        let (ra, rb) = (find(&mut parent, a), find(&mut parent, b));
        if ra == rb {
            continue;
        }
        if size[ra] + size[rb] > SIMILAR_GROUP_LIMIT {
            continue;
        }
        let (keep, gone) = if ra < rb { (ra, rb) } else { (rb, ra) };
        parent[gone] = keep;
        size[keep] += size[gone];
        widest[keep] = widest[keep].max(widest[gone]).max(distance);
    }
    let mut members: Vec<Vec<usize>> = vec![Vec::new(); n];
    for i in 0..n {
        let root = find(&mut parent, i);
        members[root].push(i);
    }
    let mut groups: Vec<(usize, MatchGroup)> = members
        .into_iter()
        .enumerate()
        .filter(|(_, m)| m.len() > 1)
        .map(|(root, m)| {
            let first = m[0];
            let names: Vec<String> = m.iter().map(|&i| ids[i].clone()).collect();
            let group = MatchGroup {
                id: format!("similar:{}", ids[first]),
                kind: MatchKind::Similar,
                reason: reason(measure, names.len(), widest[root]),
                members: names,
            };
            (first, group)
        })
        .collect();
    groups.sort_by_key(|(first, _)| *first);
    groups.into_iter().map(|(_, g)| g).collect()
}

/// Collects the visit order and qualifying pairs during a similarity pass, then groups them.
#[derive(Debug, uniffi::Object)]
pub struct SimilarClusters {
    measure: SimilarityMeasure,
    order: Mutex<Vec<String>>,
    edges: Mutex<Vec<SimilarEdge>>,
}

#[uniffi::export]
impl SimilarClusters {
    #[uniffi::constructor]
    pub fn new(measure: SimilarityMeasure) -> Self {
        Self {
            measure,
            order: Mutex::new(Vec::new()),
            edges: Mutex::new(Vec::new()),
        }
    }

    /// Records a photo in visit order (before linking it to earlier ones).
    pub fn visit(&self, id: String) {
        self.order.lock().unwrap().push(id);
    }

    /// Keeps the pair when it qualifies; returns whether it did.
    pub fn link(&self, earlier: String, later: String, distance: f32) -> bool {
        if !similar_edge_qualifies(distance, self.measure) {
            return false;
        }
        self.edges.lock().unwrap().push(SimilarEdge {
            earlier,
            later,
            distance,
        });
        true
    }

    pub fn groups(&self) -> Vec<MatchGroup> {
        similar_clusters(
            self.order.lock().unwrap().clone(),
            self.edges.lock().unwrap().clone(),
            self.measure,
        )
    }
}
