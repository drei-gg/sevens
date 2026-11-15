#if DEBUG
import Foundation
import FastCore

enum DemoArchive {
    static func make(now: Date = Date()) -> FastArchive {
        var archive = FastArchive()
        let calendar = Calendar.current
        for offset in stride(from: 86, through: 1, by: -1) where offset % 7 != 0 && offset % 9 != 0 {
            let day = calendar.date(byAdding: .day, value: -offset, to: calendar.startOfDay(for: now))!
            let end = calendar.date(byAdding: .hour, value: 12, to: day)!
            let hours = [14, 16, 15, 17, 12][offset % 5]
            let fast = FastRecord(start: end.addingTimeInterval(-Double(hours) * 3_600), end: end,
                intention: offset % 3 == 0 ? "Make room for a quieter morning." : "",
                reflection: offset % 4 == 0 ? "A walk helped. I ate when I was ready." : "",
                tags: offset % 3 == 0 ? ["morning walk"] : [])
            archive.fasts.append(fast)
            if offset % 3 == 0 {
                archive.journal.append(JournalEntry(date: end, fastID: fast.id,
                    text: "A steady morning. Slept well and took my time with breakfast.", mood: 4, energy: 4, hunger: 2,
                    tags: ["slept well"]))
            }
            if offset % 5 == 0 { archive.metrics.append(MetricEntry(date: end, kind: .weight, value: 72 + Double(offset) / 100)) }
        }
        archive.plans = [PlannedFast(start: now.addingTimeInterval(86_400), intention: "An ordinary day, at my own pace.")]
        archive.restDays = [RestDay.on(calendar.date(byAdding: .day, value: -7, to: now)!)]
        return archive
    }
}
#endif
